package com.agentscope.brain.conversation.internal.application;

import com.agentscope.brain.identity.api.CurrentUser;
import com.agentscope.brain.release.api.SubmissionRelease;
import com.agentscope.brain.runtime.api.OrdinaryDispatch;
import com.agentscope.brain.memory.api.InputAdmission;
import com.agentscope.brain.projection.api.AcceptedTextProjection;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TextSubmissionContractTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final CurrentUser users=mock(CurrentUser.class);
    private final SubmissionRelease releases=mock(SubmissionRelease.class);
    private final OrdinaryDispatch queue=mock(OrdinaryDispatch.class);
    private final InputAdmission memory=mock(InputAdmission.class);
    private final AcceptedTextProjection projection=mock(AcceptedTextProjection.class);
    private final PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
    private final TextSubmission service=new TextSubmission(jdbc,users,releases,queue,memory,projection,Clock.systemUTC(),transactions,true);
    private void invalid(TextSubmission.Payload input,String code) {
        assertThatThrownBy(() -> service.submit(input)).isInstanceOfSatisfying(SessionReadFailure.class,e -> {
            assertThat(e.status()).isEqualTo(400); assertThat(e.getMessage()).isEqualTo(code);
        });
        verifyNoInteractions(jdbc,users,releases,queue,memory,projection,transactions);
    }
    @Test void ambiguousTargetsCannotReachRegistrationOrQueue() {
        invalid(new TextSubmission.Payload(UUID.randomUUID().toString(),new TextSubmission.Target("1","2"),"text",List.of()),"TARGET_INVALID");
        invalid(new TextSubmission.Payload(UUID.randomUUID().toString(),new TextSubmission.Target(null,null),"text",List.of()),"TARGET_INVALID");
    }
    @Test void malformedKeysNeverAllocateBusinessFacts() {
        invalid(new TextSubmission.Payload("1-1-1-1-1",new TextSubmission.Target("1",null),"text",List.of()),"REQUEST_INVALID");
    }
    @Test void imagesCannotSilentlyBecomeTextOnly() {
        invalid(new TextSubmission.Payload(UUID.randomUUID().toString(),new TextSubmission.Target("1",null),"text",List.of("upload")),"UPLOAD_SET_INVALID");
    }
    @Test void invalidUtf8AndOversizeInputCannotCreateControlRows() {
        invalid(new TextSubmission.Payload(UUID.randomUUID().toString(),new TextSubmission.Target("1",null),"😀".repeat(65537),List.of()),"REQUEST_INVALID");
        invalid(new TextSubmission.Payload(UUID.randomUUID().toString(),new TextSubmission.Target("1",null),String.valueOf((char)0xD800),List.of()),"REQUEST_INVALID");
    }
    @Test void blankMessagesCannotCreateEmptySessions() {
        invalid(new TextSubmission.Payload(UUID.randomUUID().toString(),new TextSubmission.Target("1",null)," \n ",List.of()),"REQUEST_INVALID");
    }
    @Test void unsignedIdentifiersAreValidatedWithoutFloatingPointConversion() {
        invalid(new TextSubmission.Payload(UUID.randomUUID().toString(),new TextSubmission.Target("18446744073709551616",null),"text",List.of()),"TARGET_INVALID");
    }
}