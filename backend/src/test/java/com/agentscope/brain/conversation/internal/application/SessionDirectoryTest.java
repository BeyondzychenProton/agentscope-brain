package com.agentscope.brain.conversation.internal.application;

import com.agentscope.brain.identity.api.CurrentUser;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionDirectoryTest {
    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:directory-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
    private final CurrentUser users = mock(CurrentUser.class);
    private final String key = Base64.getEncoder().encodeToString(new byte[32]);
    private final SessionListCursor cursors = new SessionListCursor(key);
    private final SessionDirectory directory = new SessionDirectory(jdbc, users, cursors);
    private final Instant at = Instant.parse("2026-10-27T02:03:04.123456Z");
    SessionDirectoryTest() {
        jdbc.execute("CREATE TABLE digital_employee(id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE employee_use_grant(user_id BIGINT,employee_id BIGINT,grant_status VARCHAR(20))");
        jdbc.execute("CREATE TABLE business_session(id DECIMAL(20,0) PRIMARY KEY,user_id BIGINT,employee_id BIGINT,title VARCHAR(200),row_version BIGINT,is_archived INT,last_activity_at TIMESTAMP(6))");
        jdbc.execute("CREATE TABLE business_run(id BIGINT PRIMARY KEY,session_id DECIMAL(20,0),actor_user_id BIGINT,status VARCHAR(24))");
        jdbc.update("INSERT INTO digital_employee VALUES(1),(2),(3)");
        jdbc.update("INSERT INTO employee_use_grant VALUES(1,1,'ACTIVE'),(1,2,'ACTIVE'),(2,1,'ACTIVE')");
        actor("1", false);
        for (int i=1; i<=60; i++) session(String.valueOf(i),1,1,"对话 " + i,false,at);
        session("101",2,1,"其他用户的秘密标题",false,at.plusSeconds(1));
        session("102",1,1,"已归档",true,at.plusSeconds(1));
        session("103",1,2,"其他员工",false,at.plusSeconds(1));
    }
    private void actor(String id, boolean restricted) {
        when(users.require()).thenReturn(new CurrentUser.User(id,"用户","***",restricted,at,at.plusSeconds(86400)));
    }
    private void session(String id,int user,int employee,String title,boolean archived,Instant time) {
        jdbc.update("INSERT INTO business_session VALUES(?,?,?,?,1,?,?)",id,user,employee,title,archived?1:0,LocalDateTime.ofInstant(time, ZoneOffset.UTC));
    }
    private void invalid(String employee,String query,String cursor,int limit,int status,String code) {
        assertThatThrownBy(() -> directory.list(employee,query,cursor,limit))
            .isInstanceOfSatisfying(SessionReadFailure.class,e -> {
                assertThat(e.status()).isEqualTo(status); assertThat(e.getMessage()).isEqualTo(code);
            });
    }
    @Test void stableKeysetExcludesForeignAndArchivedSessions() {
        var seen = new HashSet<String>();
        var first = directory.list("1",null,null,7);
        assertThat(first.items()).extracting(SessionDirectory.Summary::sessionId).containsExactly("60","59","58","57","56","55","54");
        assertThat(first.items().get(0).updatedAt()).isEqualTo(at.toString());
        first.items().forEach(s -> assertThat(seen.add(s.sessionId())).isTrue());
        session("104",1,1,"后来到达",false,at.plusSeconds(1));
        String cursor = first.nextPageCursor();
        while (cursor != null) {
            var page = directory.list("1","",cursor,7);
            page.items().forEach(s -> assertThat(seen.add(s.sessionId())).isTrue());
            cursor = page.nextPageCursor();
        }
        assertThat(seen).hasSize(60).doesNotContain("101","102","103","104");
    }
    @Test void searchCoversUnloadedTitlesAndLiteralWildcards() {
        assertThat(directory.list("1","",null,1).items().get(0).sessionId()).isEqualTo("60");
        assertThat(directory.list("1","对话 1",null,100).items()).hasSize(11);
        session("111",1,1,"预算100%_!中文😀",false,at);
        for (String query : new String[]{"%","_","!","中文😀"," 100%_! "}) {
            assertThat(directory.list("1",query,null,100).items()).extracting(SessionDirectory.Summary::sessionId).containsExactly("111");
        }
        assertThat(directory.list("1","' OR 1=1 --",null,100).items()).isEmpty();
        assertThat(directory.list("1","秘密",null,100).items()).isEmpty();
    }
    @Test void unsignedIdsRemainExactBeyondJavascriptAndSignedLongRanges() {
        session("18446744073709551615",1,1,null,false,at);
        var page = directory.list("1","",null,1);
        assertThat(page.items().get(0).sessionId()).isEqualTo("18446744073709551615");
        assertThat(page.items().get(0).title()).isNull();
        assertThat(directory.list("1","",page.nextPageCursor(),1).items().get(0).sessionId()).isEqualTo("60");
    }
    @Test void cursorBindsActorEmployeeQueryPurposeAndSignature() {
        String cursor = directory.list("1","对话",null,1).nextPageCursor();
        invalid("1","其他",cursor,1,409,"CURSOR_INVALID");
        invalid("2","对话",cursor,1,409,"CURSOR_INVALID");
        actor("2",false); invalid("1","对话",cursor,1,409,"CURSOR_INVALID"); actor("1",false);
        invalid("1","对话",cursor+"x",1,409,"CURSOR_INVALID");
        invalid("1","","",1,409,"CURSOR_INVALID");
        invalid("1","","x".repeat(4097),1,409,"CURSOR_INVALID");
        var wrong = cursors.encode(new SessionListCursor.Position("messages","1","1","",at.toString(),"60"));
        invalid("1","",wrong,1,409,"CURSOR_INVALID");
    }
    @Test void configuredKeySurvivesRestartAndRandomKeyRequiresNewSearch() {
        String cursor = directory.list("1","",null,1).nextPageCursor();
        var restarted = new SessionDirectory(jdbc,users,new SessionListCursor(key));
        assertThat(restarted.list("1","",cursor,1).items().get(0).sessionId()).isEqualTo("59");
        assertThatThrownBy(() -> new SessionDirectory(jdbc,users,new SessionListCursor("")).list("1","",cursor,1))
            .isInstanceOf(SessionReadFailure.class);
    }
    @Test void revokedOrMissingGrantPreservesOwnedHistoryWithoutWriteActions() {
        jdbc.update("UPDATE employee_use_grant SET grant_status='REVOKED' WHERE user_id=1");
        assertThat(directory.list("1","",null,50).items()).hasSize(50).allSatisfy(s -> assertThat(s.allowedActions()).isEmpty());
        jdbc.update("DELETE FROM employee_use_grant WHERE user_id=1");
        assertThat(directory.list("1","",null,50).items()).hasSize(50);
        invalid("3","",null,50,404,"NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_run",Integer.class)).isZero();
    }
    @Test void activeSummaryUsesPersistedRunWithoutInventingDispatchState() {
        jdbc.update("INSERT INTO business_run VALUES(1,60,1,'ACCEPTED'),(2,59,1,'SUCCEEDED')");
        var page = directory.list("1","",null,2);
        assertThat(page.items().get(0).activeRun()).isEqualTo(new SessionDirectory.ActiveRun("1","ACCEPTED",null));
        assertThat(page.items().get(1).activeRun()).isNull();
        jdbc.update("INSERT INTO business_run VALUES(3,60,1,'RUNNING')");
        invalid("1","",null,2,503,"SESSION_READ_UNAVAILABLE");
    }
    @Test void everyReadRequiresCurrentIdentityAndValidBounds() {
        actor("1",true); invalid("1","",null,50,403,"PASSWORD_CHANGE_REQUIRED"); actor("1",false);
        invalid("0","",null,50,404,"NOT_FOUND");
        invalid("18446744073709551616","",null,50,404,"NOT_FOUND");
        invalid("1","",null,0,400,"REQUEST_INVALID"); invalid("1","",null,101,400,"REQUEST_INVALID");
        invalid("1","中".repeat(201),null,50,400,"REQUEST_INVALID");
        invalid("1","a" + (char)10 + "b",null,50,400,"REQUEST_INVALID");
        invalid("1",String.valueOf((char)0xD800),null,50,400,"REQUEST_INVALID");
        when(users.require()).thenThrow(new IllegalStateException("account invalid"));
        assertThatThrownBy(() -> directory.list("1","",null,50)).hasMessage("account invalid");
    }
}
