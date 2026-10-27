package com.agentscope.brain.projection.internal.application;

import com.agentscope.brain.projection.api.AcceptedTextProjection;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AcceptedTextWriter implements AcceptedTextProjection {
    private final JdbcTemplate jdbc;
    private final HistoryCursor cursors;
    private final ObjectMapper json=new ObjectMapper();
    public AcceptedTextWriter(JdbcTemplate jdbc,HistoryCursor cursors) { this.jdbc=jdbc;this.cursors=cursors; }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void append(Fact f) {
        try {
            byte[] body=f.text().getBytes(StandardCharsets.UTF_8);
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(body);
            String contentId=UUID.randomUUID().toString();
            // USER stays plain-text; ASSISTANT uses markdown-v1 per workbench §6 / #21 Q19.
            String bodyFormat="ASSISTANT".equals(f.senderKind()) ? "markdown-v1" : "plain-text";
            var ref=Map.of("contentId",contentId,"contentVersion","1","kind","TEXT","bodyFormat",bodyFormat,
                    "byteCount",String.valueOf(body.length),"sha256",HexFormat.of().formatHex(digest));
            var content=new LinkedHashMap<String,Object>(ref);
            content.putAll(Map.of("type","CONTENT_SET","runId",f.runId(),"baseContentVersion","0","firstSeq",f.seq(),
                    "firstPosition",3,"status","CLOSED","closeReason","COMPLETED"));
            var message=new LinkedHashMap<String,Object>(Map.of("type","MESSAGE_COMMIT","messageId",f.messageId(),"messageNo",f.messageNo(),
                    "senderKind",f.senderKind(),"runId",f.runId(),"acceptedAt",
                    f.now().format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")),
                    "contents",List.of(ref),"replacesContentIds",f.replacesContentIds()));
            if("USER".equals(f.senderKind())) message.put("initialRunId",f.runId());
            else message.put("producedByRunId",f.runId());
            var session=new LinkedHashMap<String,Object>(Map.of("type","SESSION_SET","sessionId",f.sessionId(),"employeeId",f.employeeId(),
                    "sessionVersion",f.sessionVersion(),"viewRevision",f.seq(),"lifecycleStatus","ACTIVE","recoveryStatus","READY"));
            session.put("title",f.title());
            var run=Map.of("type","RUN_SET","runId",f.runId(),"status",f.runStatus(),"viewRevision",f.seq());
            String cursor=cursors.encode(new HistoryCursor.Position("stream",f.userId(),f.sessionId(),f.streamId(),f.seq(),f.messageNo(),"0"));
            byte[] payload=json.writeValueAsBytes(Map.of("protocolVersion",TextHistory.PROTOCOL,"sessionId",f.sessionId(),"streamId",f.streamId(),
                    "seq",f.seq(),"previousSeq",f.previousSeq(),"cursor",cursor,"occurredAt",f.now().format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")),
                    "changes",List.of(session,run,content,message)));
            if(body.length>262144 || payload.length>1048576) { throw new IllegalArgumentException(); }
            jdbc.update("""
                    INSERT INTO projection_content(content_id,owner_user_id,session_id,run_id,content_kind,stream_id,
                        first_seq,first_position,current_version,status,close_reason,gmt_create,gmt_modified)
                    VALUES(?,?,?,?,'TEXT',?,?,3,1,'CLOSED','COMPLETED',?,?)
                    """,uuid(contentId),f.userId(),f.sessionId(),f.runId(),uuid(f.streamId()),f.seq(),f.now(),f.now());
            jdbc.update("""
                    INSERT INTO projection_content_version(content_id,content_version,base_version,change_kind,content_status,close_reason,
                        body_format,payload_storage,body,byte_count,body_sha256,source_kind,source_id,projection_seq,projection_position,gmt_create,gmt_modified)
                    VALUES(?,1,0,'CONTENT_SET','CLOSED','COMPLETED',?,'INLINE',?,?,?,'MESSAGE_BLOCK',?,?,3,?,?)
                    """,uuid(contentId),bodyFormat,body,body.length,digest,f.blockId(),f.seq(),f.now(),f.now());
            jdbc.update("""
                    INSERT INTO session_projection_event(session_id,stream_id,seq,previous_seq,protocol_version,batch_payload,
                        byte_count,batch_sha256,change_count,occurred_at,gmt_create,gmt_modified)
                    VALUES(?,?,?,?,'web-projection-v1',?,?,?,4,?,?,?)
                    """,f.sessionId(),uuid(f.streamId()),f.seq(),f.previousSeq(),payload,payload.length,
                    MessageDigest.getInstance("SHA-256").digest(payload),f.now(),f.now(),f.now());
        } catch (RuntimeException failure) { throw failure; }
        catch (Exception invalid) { throw new IllegalStateException("PROJECTION_WRITE_UNAVAILABLE"); }
    }
    private static byte[] uuid(String value) {
        var id=UUID.fromString(value); return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
