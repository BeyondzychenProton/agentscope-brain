package com.agentscope.brain.conversation.internal.application;

import com.agentscope.brain.identity.api.CurrentUser;
import com.agentscope.brain.release.api.SubmissionRelease;
import com.agentscope.brain.runtime.api.OrdinaryDispatch;
import com.agentscope.brain.memory.api.InputAdmission;
import com.agentscope.brain.projection.api.AcceptedTextProjection;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Statement;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Pre-registration, admission, lookup and permanent close are separate transactions. No execution dependency. */
@Service
public class TextSubmission {
    public record Target(String intentId,String sessionId) { }
    public record Payload(String submissionKey,Target target,String text,List<String> uploadIds) { }
    public static class IntentAlreadyAccepted extends SessionReadFailure {
        private final Result result;
        public IntentAlreadyAccepted(Result result) { super(409,"INTENT_ALREADY_ACCEPTED");this.result=result; }
        public Result result() { return result; }
    }
    public record Prepare(String requestKey,String clientIntentId,String employeeId) { }
    public record Close(String requestKey,String expectedSubmissionVersion) { }
    public record Result(String sessionId,String messageId,String runId) { }
    public record Receipt(String submissionKey,String registrationStatus,String submissionVersion,
            String acceptanceStatus,String processingStatus,Result result,String reason,List<String> allowedActions) { }
    public record Intent(String intentId,String clientIntentId,String employeeId,String preparationContext,
            String intentVersion,String status,String acceptedSubmissionKey,String sessionId,String messageId,String runId) { }
    private record Located(String employeeId,String releaseId,Map<String,Object> intent) { }
    private final JdbcTemplate jdbc;
    private final CurrentUser users;
    private final SubmissionRelease releases;
    private final OrdinaryDispatch queue;
    private final InputAdmission memory;
    private final AcceptedTextProjection projection;
    private final Clock clock;
    private final boolean enabled;
    private final TransactionTemplate write;
    private final TransactionTemplate read;
    private final ObjectMapper json=new ObjectMapper();

    public TextSubmission(JdbcTemplate jdbc,CurrentUser users,SubmissionRelease releases,OrdinaryDispatch queue,
            InputAdmission memory,AcceptedTextProjection projection,Clock clock,PlatformTransactionManager transactions,
            @Value("${brain.conversation.text-submission-enabled:false}") boolean enabled) {
        this.jdbc=jdbc;this.users=users;this.releases=releases;this.queue=queue;this.memory=memory;this.projection=projection;
        this.clock=clock;this.enabled=enabled;
        write=new TransactionTemplate(transactions); write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        read=new TransactionTemplate(transactions); read.setReadOnly(true);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Intent prepare(Prepare input) {
        if(input==null) { throw failure(400,"REQUEST_INVALID"); }
        uuid(input.requestKey()); byte[] client=uuid(input.clientIntentId()); id(input.employeeId());
        return write.execute(status -> {
            var user=ready(true);
            var existing=jdbc.queryForList("SELECT * FROM session_intent WHERE owner_user_id=? AND client_intent_id=?",user.userId(),client);
            if(!existing.isEmpty()) {
                if(!input.employeeId().equals(value(existing.get(0),"employee_id"))) { throw failure(409,"IDEMPOTENCY_CONFLICT"); }
                return intent(existing.get(0));
            }
            requireEnabled();
            var release=releases.lock(user.userId(),input.employeeId(),null,true);
            var now=now();
            insert("""
                INSERT INTO session_intent(owner_user_id,client_intent_id,employee_id,employee_release_id,image_capability_sha256,
                    image_capability_json,prepared_at,gmt_create,gmt_modified) VALUES(?,?,?,?,?,?,?,?,?)
                """,user.userId(),client,input.employeeId(),release.releaseId(),release.imagePolicySha256(),release.imagePolicyJson(),now,now,now);
            return intent(jdbc.queryForList("SELECT * FROM session_intent WHERE owner_user_id=? AND client_intent_id=?",user.userId(),client).get(0));
        });
    }
    public Intent intent(String intentId) {
        id(intentId);
        return read.execute(status -> {
            var user=ready(false);
            return intent(ownedIntent(intentId,user.userId(),false));
        });
    }
    public Receipt submit(Payload input) {
        validate(input);
        byte[] hash=semantic(input);
        Receipt registered=write.execute(status -> register(input,hash));
        if("ACCEPTED".equals(registered.registrationStatus())) { return registered; }
        return write.execute(status -> accept(input,hash));
    }
    public Receipt lookup(String key) {
        byte[] bytes=uuid(key);
        return read.execute(status -> {
            var user=ready(false);
            var rows=control(user.userId(),bytes,false);
            if(!rows.isEmpty()) { return receipt(rows.get(0)); }
            if(formalExists(user.userId(),bytes)) { return unknown(key,"RECONCILING","CONTROL_REQUIRES_RECONCILIATION"); }
            return unknown(key,"UNOBSERVED",null);
        });
    }
    public Receipt close(String key,Close input) {
        byte[] bytes=uuid(key);
        if(input==null) { throw failure(400,"REQUEST_INVALID"); }
        byte[] request=uuid(input.requestKey());
        if(input.expectedSubmissionVersion()!=null) { id(input.expectedSubmissionVersion()); }
        byte[] hash=sha((key+"|"+String.valueOf(input.expectedSubmissionVersion())).getBytes(StandardCharsets.UTF_8));
        return write.execute(status -> {
            var user=ready(true);
            var rows=control(user.userId(),bytes,true);
            if(rows.isEmpty()) {
                if(formalExists(user.userId(),bytes)) { throw failure(409,"RECONCILING"); }
                var now=now();
                insert("""
                    INSERT INTO ordinary_submission(user_id,submission_key,has_registered_intent,status,closed_at,
                        reason_code,close_request_key,close_request_sha256,gmt_create,gmt_modified)
                    VALUES(?,?,0,'CLOSED_UNACCEPTED',?,'USER_CLOSED',?,?,?,?)
                    """,user.userId(),bytes,now,request,hash,now,now);
            } else {
                var row=rows.get(0);
                if(Arrays.equals(request,(byte[])row.get("close_request_key")) &&
                        !MessageDigest.isEqual(hash,(byte[])row.get("close_request_sha256"))) { throw failure(409,"IDEMPOTENCY_CONFLICT"); }
                if("ACCEPTED".equals(row.get("status")) || "CLOSED_UNACCEPTED".equals(row.get("status"))) { return receipt(row); }
                if("RECONCILING".equals(row.get("status"))) { throw failure(409,"RECONCILING"); }
                if(input.expectedSubmissionVersion()!=null && !input.expectedSubmissionVersion().equals(value(row,"row_version"))) {
                    throw failure(409,"STALE_REVISION");
                }
                var now=now();
                jdbc.update("""
                    UPDATE ordinary_submission SET status='CLOSED_UNACCEPTED',closed_at=?,reason_code='USER_CLOSED',
                        close_request_key=?,close_request_sha256=?,row_version=row_version+1,acceptance_fence=acceptance_fence+1,
                        gmt_modified=? WHERE id=?
                    """,now,request,hash,now,row.get("id"));
            }
            return receipt(control(user.userId(),bytes,false).get(0));
        });
    }
    private Receipt register(Payload p,byte[] hash) {
        var user=ready(true); byte[] key=uuid(p.submissionKey());
        var existing=control(user.userId(),key,false);
        if(!existing.isEmpty()) { same(existing.get(0),hash);return receipt(existing.get(0)); }
        requireEnabled();
        if(formalExists(user.userId(),key)) { throw failure(409,"RECONCILING"); }
        var located=locate(p.target(),user.userId(),false);
        releases.lock(user.userId(),located.employeeId(),located.releaseId(),p.target().intentId()!=null);
        if(p.target().intentId()!=null) { located=locate(p.target(),user.userId(),true); }
        var now=now();
        insert("""
            INSERT INTO ordinary_submission(user_id,submission_key,has_registered_intent,target_session_id,session_intent_id,
                employee_id,submission_sha256,upload_ref_json,status,gmt_create,gmt_modified)
            VALUES(?,?,1,?,?,?,?,'[]','REGISTERED',?,?)
            """,user.userId(),key,p.target().sessionId(),p.target().intentId(),located.employeeId(),hash,now,now);
        return receipt(control(user.userId(),key,false).get(0));
    }
    private Receipt accept(Payload p,byte[] hash) {
        var capacity=queue.lockCapacity(); // C0 first.
        var user=ready(true); byte[] key=uuid(p.submissionKey());
        var located=locate(p.target(),user.userId(),false);
        var release=releases.lock(user.userId(),located.employeeId(),located.releaseId(),p.target().intentId()!=null);
        if(p.target().intentId()!=null) { located=locate(p.target(),user.userId(),true); }
        var row=control(user.userId(),key,true).get(0); same(row,hash);
        if("ACCEPTED".equals(row.get("status"))) { return receipt(row); }
        requireEnabled();
        if(!"REGISTERED".equals(row.get("status"))) { throw failure(409,"RECONCILING"); }
        if(located.intent()!=null && located.intent().get("bound_session_id")!=null) {
            var original=intent(located.intent());
            throw new IntentAlreadyAccepted(new Result(original.sessionId(),original.messageId(),original.runId()));
        }
        if(located.intent()!=null && (located.intent().get("prepare_invalid_reason")!=null ||
                !Arrays.equals((byte[])located.intent().get("image_capability_sha256"),release.imagePolicySha256()))) {
            throw failure(409,"PREPARATION_STALE");
        }
        var now=now();
        var mem=memory.allocate(user.userId(),located.employeeId(),now); // L6, no KV initialization.
        Map<String,Object> session;
        if(p.target().sessionId()==null) {
            String title=title(p.text());
            String sid=insert("""
                INSERT INTO business_session(user_id,employee_id,employee_release_id,state_session_key,title,last_activity_at,
                    projection_stream_id,gmt_create,gmt_modified) VALUES(?,?,?,?,?,?,?,?,?)
                """,user.userId(),located.employeeId(),release.releaseId(),UUID.randomUUID().toString().replace("-",""),
                    title,now,uuid(UUID.randomUUID().toString()),now,now);
            session=ownedSession(sid,user.userId(),true);
        } else { session=ownedSession(p.target().sessionId(),user.userId(),true); }
        String sid=value(session,"id");
        if(!"ACTIVE".equals(session.get("lifecycle_status")) || !"READY".equals(session.get("recovery_status")) ||
                session.get("source_session_id")!=null || session.get("projection_stream_id")==null) { throw failure(409,"SESSION_CHANGED"); }
        if(!jdbc.queryForList("SELECT id FROM business_run WHERE session_id=? AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED') FOR UPDATE",sid).isEmpty()) {
            throw failure(409,"RUN_BUSY");
        }
        String no=plus(value(session,"last_message_no")),seq=plus(value(session,"last_projection_seq")),version=plus(value(session,"row_version"));
        String mid=insert("""
            INSERT INTO business_message(session_id,user_id,employee_id,message_no,input_sequence,sender_kind,submission_key,
                submission_sha256,accepted_at,gmt_create,gmt_modified) VALUES(?,?,?,?,?,'USER',?,?,?,?,?)
            """,sid,user.userId(),located.employeeId(),no,mem.inputSequence(),key,hash,now,now,now);
        String block=insert("INSERT INTO message_content_block(message_id,block_no,block_kind,text_content,gmt_create,gmt_modified) VALUES(?,1,'TEXT',?,?,?)",
                mid,p.text(),now,now);
        String run=insert("""
            INSERT INTO business_run(session_id,input_message_id,actor_user_id,employee_release_id,memory_generation_at_start,
                memory_revision_at_start,memory_policy_version,run_request_key,request_sha256,status,gmt_create,gmt_modified)
            VALUES(?,?,?,?,?,?,?,?,?,'ACCEPTED',?,?)
            """,sid,mid,user.userId(),release.releaseId(),mem.generation(),mem.documentRevision(),mem.policyVersion(),key,hash,now,now);
        jdbc.update("UPDATE business_message SET initial_run_id=? WHERE id=?",run,mid);
        queue.enqueue(capacity,new OrdinaryDispatch.Work(user.userId(),located.employeeId(),release.releaseId(),sid,run,value(row,"id"),key,hash,now));
        jdbc.update("""
            UPDATE business_session SET last_message_no=?,last_projection_seq=?,row_version=?,last_activity_at=?,gmt_modified=? WHERE id=?
            """,no,seq,version,now,now,sid);
        jdbc.update("""
            UPDATE ordinary_submission SET status='ACCEPTED',accepted_session_id=?,accepted_message_id=?,accepted_run_id=?,
                accepted_at=?,row_version=row_version+1,acceptance_fence=acceptance_fence+1,gmt_modified=? WHERE id=?
            """,sid,mid,run,now,now,row.get("id"));
        if(located.intent()!=null) {
            jdbc.update("UPDATE session_intent SET bound_session_id=?,first_submission_id=?,bound_at=?,row_version=row_version+1,gmt_modified=? WHERE id=?",
                    sid,row.get("id"),now,now,located.intent().get("id"));
        }
        projection.append(new AcceptedTextProjection.Fact(user.userId(),located.employeeId(),sid,release.releaseId(),
                (String)session.get("title"),version,uuid((byte[])session.get("projection_stream_id")),seq,
                value(session,"last_projection_seq"),mid,no,block,run,p.text(),now));
        jdbc.update("""
            INSERT INTO conversation_audit_event(actor_user_id,target_user_id,session_id,run_id,action_code,outcome,
                entry_kind,occurred_at,gmt_create,gmt_modified) VALUES(?,?,?,?,'ORDINARY_TEXT_ACCEPTED','SUCCESS','WEB',?,?,?)
            """,user.userId(),user.userId(),sid,run,now,now,now);
        return receipt(control(user.userId(),key,false).get(0));
    }
    private Located locate(Target target,String user,boolean lockIntent) {
        if(target.intentId()!=null) {
            var i=ownedIntent(target.intentId(),user,lockIntent);
            return new Located(value(i,"employee_id"),value(i,"employee_release_id"),i);
        }
        var s=ownedSession(target.sessionId(),user,false);
        return new Located(value(s,"employee_id"),value(s,"employee_release_id"),null);
    }
    private Map<String,Object> ownedIntent(String id,String user,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM session_intent WHERE id=? AND owner_user_id=?"+(lock?" FOR UPDATE":""),id,user);
        if(rows.size()!=1) { throw failure(404,"NOT_FOUND"); } return rows.get(0);
    }
    private Map<String,Object> ownedSession(String id,String user,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM business_session WHERE id=? AND user_id=?"+(lock?" FOR UPDATE":""),id,user);
        if(rows.size()!=1) { throw failure(404,"NOT_FOUND"); } return rows.get(0);
    }
    private List<Map<String,Object>> control(String user,byte[] key,boolean lock) {
        return jdbc.queryForList("SELECT * FROM ordinary_submission WHERE user_id=? AND submission_key=?"+(lock?" FOR UPDATE":""),user,key);
    }
    private boolean formalExists(String user,byte[] key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM business_message WHERE user_id=? AND submission_key=?",Long.class,user,key)>0;
    }
    private Receipt receipt(Map<String,Object> row) {
        String status=value(row,"status"),processing=null;
        Result result=null;
        if("ACCEPTED".equals(status)) {
            result=new Result(value(row,"accepted_session_id"),value(row,"accepted_message_id"),value(row,"accepted_run_id"));
            var dispatch=jdbc.queryForList("SELECT status FROM runtime_dispatch WHERE run_id=? ORDER BY id DESC LIMIT 1",result.runId());
            if(!dispatch.isEmpty()) { processing=value(dispatch.get(0),"status"); }
        }
        return new Receipt(uuid((byte[])row.get("submission_key")),status,value(row,"row_version"),
                "ACCEPTED".equals(status)?"ACCEPTED":"CLOSED_UNACCEPTED".equals(status)?"CLOSED_UNACCEPTED":"UNKNOWN",
                processing,result,(String)row.get("reason_code"),"REGISTERED".equals(status)?List.of("CLOSE"):List.of());
    }
    private Intent intent(Map<String,Object> row) {
        Receipt receipt=null;
        if(row.get("first_submission_id")!=null) {
            var rows=jdbc.queryForList("SELECT * FROM ordinary_submission WHERE id=? AND user_id=?",row.get("first_submission_id"),row.get("owner_user_id"));
            if(rows.size()!=1 || !"ACCEPTED".equals(rows.get(0).get("status"))) { throw failure(409,"RECONCILING"); }
            receipt=receipt(rows.get(0));
        }
        return new Intent(value(row,"id"),uuid((byte[])row.get("client_intent_id")),value(row,"employee_id"),
                "intent:"+value(row,"id")+":"+value(row,"employee_release_id"),value(row,"row_version"),
                receipt!=null?"ACCEPTED":row.get("prepare_invalid_reason")==null?"PREPARED":"INVALID",
                receipt==null?null:receipt.submissionKey(),receipt==null?null:receipt.result().sessionId(),
                receipt==null?null:receipt.result().messageId(),receipt==null?null:receipt.result().runId());
    }
    private CurrentUser.User ready(boolean lock) {
        var user=lock?users.requireLocked():users.require();
        if(user.passwordChangeRequired()) { throw failure(403,"PASSWORD_CHANGE_REQUIRED"); } return user;
    }
    private void requireEnabled() { if(!enabled) { throw failure(503,"TEXT_SUBMISSION_UNAVAILABLE"); } }
    private void same(Map<String,Object> row,byte[] hash) {
        if(((Number)row.get("has_registered_intent")).intValue()==0) { throw failure(409,"SUBMISSION_CLOSED"); }
        if(!MessageDigest.isEqual((byte[])row.get("submission_sha256"),hash)) { throw failure(409,"IDEMPOTENCY_CONFLICT"); }
        if("CLOSED_UNACCEPTED".equals(row.get("status"))) { throw failure(409,"SUBMISSION_CLOSED"); }
    }
    private void validate(Payload p) {
        if(p==null || p.target()==null || (p.target().intentId()==null)==(p.target().sessionId()==null)) { throw failure(400,"TARGET_INVALID"); }
        uuid(p.submissionKey()); id(p.target().intentId()!=null?p.target().intentId():p.target().sessionId());
        if(p.uploadIds()==null || !p.uploadIds().isEmpty()) { throw failure(400,"UPLOAD_SET_INVALID"); }
        if(p.text()==null || p.text().isBlank() || !StandardCharsets.UTF_8.newEncoder().canEncode(p.text()) ||
                p.text().getBytes(StandardCharsets.UTF_8).length>262144) { throw failure(400,"REQUEST_INVALID"); }
    }
    private byte[] semantic(Payload p) {
        try { return sha(json.writeValueAsBytes(List.of("ordinary-text-v1",p.target().intentId()!=null?"intent":"session",
                p.target().intentId()!=null?p.target().intentId():p.target().sessionId(),p.text(),List.of()))); }
        catch(Exception invalid) { throw failure(400,"REQUEST_INVALID"); }
    }
    private String insert(String sql,Object...args) {
        var holder=new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);
            for(int i=0;i<args.length;i++) { statement.setObject(i+1,args[i]); }
            return statement;
        },holder);
        if(holder.getKey()==null) { throw new IllegalStateException("INSERT_RECEIPT_UNAVAILABLE"); }
        return holder.getKey().toString();
    }
    private static Receipt unknown(String key,String status,String reason) { return new Receipt(key,status,null,"UNKNOWN",null,null,reason,List.of()); }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS),ZoneOffset.UTC); }
    private static String title(String text) {
        String line=text.strip().replaceAll("\\s+"," ");
        return line.substring(0,line.offsetByCodePoints(0,Math.min(60,line.codePointCount(0,line.length()))));
    }
    private static String value(Map<String,Object> row,String key) { return String.valueOf(row.get(key)); }
    private static String plus(String value) { return new BigInteger(value).add(BigInteger.ONE).toString(); }
    private static void id(String value) {
        if(value==null || !value.matches("[1-9][0-9]{0,19}") || new BigInteger(value).bitLength()>64) { throw failure(400,"TARGET_INVALID"); }
    }
    private static byte[] uuid(String value) {
        try {
            var id=UUID.fromString(value);
            if(!id.toString().equals(value)) { throw new IllegalArgumentException(); }
            return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
        } catch(Exception invalid) { throw failure(400,"REQUEST_INVALID"); }
    }
    private static String uuid(byte[] value) { var b=ByteBuffer.wrap(value);return new UUID(b.getLong(),b.getLong()).toString(); }
    private static byte[] sha(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); } catch(Exception unavailable) { throw new IllegalStateException(); }
    }
    private static SessionReadFailure failure(int status,String code) { return new SessionReadFailure(status,code); }
}
