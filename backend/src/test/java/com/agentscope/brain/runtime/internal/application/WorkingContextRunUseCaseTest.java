package com.agentscope.brain.runtime.internal.application;

import com.agentscope.brain.conversation.api.FormalContentBlock;
import com.agentscope.brain.conversation.api.FormalConversationPort;
import com.agentscope.brain.conversation.api.FormalMessage;
import com.agentscope.brain.conversation.api.FormalSender;
import com.agentscope.brain.conversation.api.SessionRecoveryStatus;
import com.agentscope.brain.media.api.BoundFormalMedia;
import com.agentscope.brain.media.api.FormalMediaPort;
import com.agentscope.brain.media.api.MediaKind;
import com.agentscope.brain.media.api.MediaOriginal;
import com.agentscope.brain.media.api.MediaUnavailableException;
import com.agentscope.brain.runtime.internal.integration.ModelCaller;
import com.agentscope.brain.runtime.internal.integration.ModelCaller.ModelRequestCopy;
import com.agentscope.brain.runtime.internal.integration.ModelCaller.ModelResponse;
import com.agentscope.brain.runtime.internal.integration.WorkingContextStateAccess;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkingContextRunUseCaseTest {
    private static final String USER = "9007199254740993";
    private static final String SESSION = "session-1";
    private static final String SLOT = "state-slot-1";
    private static final byte[] IMAGE_A = "image-bytes-a".getBytes(StandardCharsets.UTF_8);
    private static final byte[] IMAGE_B = "image-bytes-b".getBytes(StandardCharsets.UTF_8);

    private InMemoryStateStore store;
    private FakeConversation conversation;
    private FakeMedia media;
    private RecordingModel model;
    private WorkingContextRunUseCase useCase;

    @BeforeEach
    void setUp() {
        store = new InMemoryStateStore();
        conversation = new FakeConversation();
        media = new FakeMedia();
        model = new RecordingModel(ModelResponse.textOnly("ok"));
        useCase = new WorkingContextRunUseCase(
                conversation, media, new WorkingContextStateAccess(store), model);
    }

    @Test
    void acceptedUserImagesEnterContextAndHydrateModelCopyWithoutWritingBytesBack() {
        media.put(USER, SESSION, "11", IMAGE_A, "image/png");
        media.put(USER, SESSION, "12", IMAGE_B, "image/png");
        var userMessage = new FormalMessage(
                "101",
                SESSION,
                USER,
                FormalSender.USER,
                List.of(
                        new FormalContentBlock.Text("see these"),
                        new FormalContentBlock.Media("11", MediaKind.IMAGE),
                        new FormalContentBlock.Media("12", MediaKind.IMAGE)));

        var result = useCase.executeTurn(new WorkingContextRunUseCase.TurnRequest(
                USER, SESSION, SLOT, List.of(userMessage), true));

        assertThat(result.sessionStatus()).isEqualTo(SessionRecoveryStatus.READY);
        assertThat(result.modelCalled()).isTrue();
        assertThat(result.failureCode()).isNull();

        var user = result.savedContext().stream()
                .filter(m -> "101".equals(m.getId()))
                .findFirst()
                .orElseThrow();
        var imageBlocks = imageDataBlocks(user);
        assertThat(imageBlocks).hasSize(2);
        assertThat(imageBlocks).extracting(DataBlock::getId).containsExactly("11", "12");
        for (var block : imageBlocks) {
            assertStableLocator(block, block.getId());
            assertNoBinaryOrPresigned(block);
        }

        var copy = result.modelRequestCopy();
        assertThat(copy).isNotNull();
        var copyUser = copy.messages().stream()
                .filter(m -> "101".equals(m.getId()))
                .findFirst()
                .orElseThrow();
        var copyImages = imageDataBlocks(copyUser);
        assertThat(copyImages).hasSize(2);
        assertThat(copyImages.get(0).getSource()).isInstanceOf(Base64Source.class);
        assertThat(((Base64Source) copyImages.get(0).getSource()).getData())
                .isEqualTo(java.util.Base64.getEncoder().encodeToString(IMAGE_A));
        assertThat(((Base64Source) copyImages.get(1).getSource()).getData())
                .isEqualTo(java.util.Base64.getEncoder().encodeToString(IMAGE_B));

        var reread = store.get(USER, SLOT, WorkingContextStateAccess.AGENT_STATE_KEY, AgentState.class).orElseThrow();
        var rereadUser = reread.getContext().stream()
                .filter(m -> "101".equals(m.getId()))
                .findFirst()
                .orElseThrow();
        for (var block : imageDataBlocks(rereadUser)) {
            assertThat(block.getSource()).isInstanceOf(URLSource.class);
            assertThat(block.getSource()).isNotInstanceOf(Base64Source.class);
        }
    }

    @Test
    void assistantBodyImageBytesBecomeFormalMediaBeforeSavedContext() {
        media.put(USER, SESSION, "11", IMAGE_A, "image/png");
        var userMessage = userTextAndImage("101", "11");
        model.response = new ModelResponse("here", List.of(IMAGE_B), List.of());

        var result = useCase.executeTurn(new WorkingContextRunUseCase.TurnRequest(
                USER, SESSION, SLOT, List.of(userMessage), true));

        assertThat(result.sessionStatus()).isEqualTo(SessionRecoveryStatus.READY);
        assertThat(media.persistCount()).isEqualTo(1);
        assertThat(media.downloadByUrlCount()).isZero();
        var assistant = result.savedContext().stream()
                .filter(m -> m.getRole() == MsgRole.ASSISTANT)
                .findFirst()
                .orElseThrow();
        var images = imageDataBlocks(assistant);
        assertThat(images).hasSize(1);
        assertStableLocator(images.get(0), images.get(0).getId());
        assertNoBinaryOrPresigned(images.get(0));
        assertThat(conversation.recoveryStatus(SESSION)).isEqualTo(SessionRecoveryStatus.READY);
    }

    @Test
    void modelOrToolHttpAddressesAreNeitherDownloadedNorSaved() {
        media.put(USER, SESSION, "11", IMAGE_A, "image/png");
        var userMessage = userTextAndImage("101", "11");
        model.response = new ModelResponse(
                "see https://cdn.example/x.png", List.of(), List.of("https://cdn.example/x.png"));

        var result = useCase.executeTurn(new WorkingContextRunUseCase.TurnRequest(
                USER, SESSION, SLOT, List.of(userMessage), true));

        assertThat(media.downloadByUrlCount()).isZero();
        assertThat(media.persistCount()).isZero();
        var saved = store.get(USER, SLOT, WorkingContextStateAccess.AGENT_STATE_KEY, AgentState.class)
                .orElseThrow();
        for (var msg : saved.getContext()) {
            for (var block : imageDataBlocks(msg)) {
                assertThat(block.getSource()).isInstanceOf(URLSource.class);
                assertThat(((URLSource) block.getSource()).getUrl().toLowerCase())
                        .doesNotStartWith("http://")
                        .doesNotStartWith("https://");
            }
        }
        assertThat(result.sessionStatus()).isEqualTo(SessionRecoveryStatus.READY);
    }

    @Test
    void mediaFailureStopsModelAndKeepsSessionRunnableWhenNoNewFormalMessage() {
        seedPriorLocatorContext("101", "11");
        media.put(USER, SESSION, "11", IMAGE_A, "image/png");
        media.failReadFor("11");
        var priorVersion = store.version(USER, SLOT);

        var result = useCase.executeTurn(new WorkingContextRunUseCase.TurnRequest(
                USER, SESSION, SLOT, List.of(userTextAndImage("101", "11")), false));

        assertThat(result.modelCalled()).isFalse();
        assertThat(result.failureCode()).isEqualTo("MEDIA_UNAVAILABLE");
        assertThat(result.sessionStatus()).isEqualTo(SessionRecoveryStatus.READY);
        assertThat(store.version(USER, SLOT)).isEqualTo(priorVersion);
        assertThat(conversation.acceptsNewRun(SESSION)).isTrue();
    }

    @Test
    void ownershipMismatchFailsModelAndKeepsPriorContext() {
        seedPriorLocatorContext("101", "11");
        media.put("other-user", SESSION, "11", IMAGE_A, "image/png");
        var priorVersion = store.version(USER, SLOT);

        var result = useCase.executeTurn(new WorkingContextRunUseCase.TurnRequest(
                USER, SESSION, SLOT, List.of(userTextAndImage("101", "11")), false));

        assertThat(result.failureCode()).isEqualTo("MEDIA_UNAVAILABLE");
        assertThat(result.sessionStatus()).isEqualTo(SessionRecoveryStatus.READY);
        assertThat(store.version(USER, SLOT)).isEqualTo(priorVersion);
    }

    @Test
    void binaryOrUnboundImageSaveIsRejectedAndPriorVersionKept() {
        seedPriorLocatorContext("101", "11");
        media.put(USER, SESSION, "11", IMAGE_A, "image/png");
        var priorVersion = store.version(USER, SLOT);
        var dirty = AgentState.builder()
                .userId(USER)
                .sessionId(SLOT)
                .context(List.of(Msg.builder()
                        .id("temp")
                        .role(MsgRole.USER)
                        .content(DataBlock.builder()
                                .id("orphan")
                                .source(new Base64Source(
                                        "image/png",
                                        java.util.Base64.getEncoder().encodeToString(IMAGE_B)))
                                .build())
                        .build()))
                .build();

        var save = useCase.saveCandidateContext(new WorkingContextRunUseCase.SaveRequest(
                USER, SESSION, SLOT, dirty, false));

        assertThat(save.saved()).isFalse();
        assertThat(store.version(USER, SLOT)).isEqualTo(priorVersion);
        assertThat(conversation.recoveryStatus(SESSION)).isEqualTo(SessionRecoveryStatus.READY);
    }

    @Test
    void saveFailureAfterNewFormalMessageEntersReconcilingAndBlocksNewRun() {
        seedPriorLocatorContext("101", "11");
        media.put(USER, SESSION, "11", IMAGE_A, "image/png");
        var priorVersion = store.version(USER, SLOT);
        var dirty = AgentState.builder()
                .userId(USER)
                .sessionId(SLOT)
                .context(List.of(Msg.builder()
                        .id("102")
                        .role(MsgRole.USER)
                        .content(DataBlock.builder()
                                .id("12")
                                .source(new URLSource("https://presigned.example/x?X-Amz-Signature=1"))
                                .build())
                        .build()))
                .build();

        var save = useCase.saveCandidateContext(new WorkingContextRunUseCase.SaveRequest(
                USER, SESSION, SLOT, dirty, true));

        assertThat(save.saved()).isFalse();
        assertThat(store.version(USER, SLOT)).isEqualTo(priorVersion);
        assertThat(conversation.recoveryStatus(SESSION)).isEqualTo(SessionRecoveryStatus.RECONCILING);
        assertThat(conversation.acceptsNewRun(SESSION)).isFalse();
        assertThatThrownBy(() -> useCase.executeTurn(new WorkingContextRunUseCase.TurnRequest(
                        USER, SESSION, SLOT, List.of(userTextAndImage("102", "12")), true)))
                .isInstanceOf(WorkingContextRunUseCase.NewRunBlockedException.class);
    }

    @Test
    void alignAppendsMissingFormalMessagesKeepsToolTrailThenReturnsReady() {
        media.put(USER, SESSION, "11", IMAGE_A, "image/png");
        media.put(USER, SESSION, "12", IMAGE_B, "image/png");
        var toolUse = Msg.builder()
                .id("tool-msg")
                .role(MsgRole.ASSISTANT)
                .content(ToolUseBlock.builder().id("t1").name("room.lookup").input(Map.of("q", "1")).build())
                .build();
        var toolResult = Msg.builder()
                .id("tool-result")
                .role(MsgRole.TOOL)
                .content(ToolResultBlock.of("t1", "room.lookup", TextBlock.builder().text("ok").build()))
                .build();
        var prior = AgentState.builder()
                .userId(USER)
                .sessionId(SLOT)
                .context(List.of(
                        assembledUser("101", "hello", "11"),
                        toolUse,
                        toolResult))
                .build();
        store.save(USER, SLOT, WorkingContextStateAccess.AGENT_STATE_KEY, prior);
        conversation.markReconciling(SESSION, "CONTEXT_SAVE_FAILED");

        var missing = new FormalMessage(
                "102",
                SESSION,
                USER,
                FormalSender.USER,
                List.of(
                        new FormalContentBlock.Text("second"),
                        new FormalContentBlock.Media("12", MediaKind.IMAGE)));
        var align = useCase.alignBeforeNextRun(new WorkingContextRunUseCase.AlignRequest(
                USER,
                SESSION,
                SLOT,
                List.of(userTextAndImage("101", "11"), missing)));

        assertThat(align.sessionStatus()).isEqualTo(SessionRecoveryStatus.READY);
        assertThat(media.readCount()).isZero();
        var context = align.savedContext();
        assertThat(context).extracting(Msg::getId).containsExactly("101", "tool-msg", "tool-result", "102");
        assertThat(imageDataBlocks(context.get(3)).get(0).getId()).isEqualTo("12");
        assertThat(conversation.acceptsNewRun(SESSION)).isTrue();
    }

    @Test
    void unreadablePriorRebuildsFromFormalDialogueWithoutToolTrail() {
        media.put(USER, SESSION, "11", IMAGE_A, "image/png");
        store.corrupt(USER, SLOT);
        conversation.markReconciling(SESSION, "CONTEXT_UNREADABLE");

        var align = useCase.alignBeforeNextRun(new WorkingContextRunUseCase.AlignRequest(
                USER, SESSION, SLOT, List.of(userTextAndImage("101", "11"))));

        assertThat(align.sessionStatus()).isEqualTo(SessionRecoveryStatus.READY);
        assertThat(align.savedContext()).extracting(Msg::getId).containsExactly("101");
        assertThat(align.savedContext()).noneMatch(m -> m.getRole() == MsgRole.TOOL);
    }

    @Test
    void newVersionSlotAssemblesFormalDialogueWithoutCopyingOldWorkingContext() {
        media.put(USER, SESSION, "11", IMAGE_A, "image/png");
        seedPriorLocatorContext("101", "11");
        var oldJson = store.get(USER, SLOT, WorkingContextStateAccess.AGENT_STATE_KEY, AgentState.class)
                .orElseThrow()
                .toJson();
        var newSlot = "state-slot-new";
        var newSession = "session-2";
        media.put(USER, newSession, "11", IMAGE_A, "image/png");
        var formal = new FormalMessage(
                "201",
                newSession,
                USER,
                FormalSender.USER,
                List.of(
                        new FormalContentBlock.Text("hello"),
                        new FormalContentBlock.Media("11", MediaKind.IMAGE)));

        var result = useCase.openNewVersionSlot(new WorkingContextRunUseCase.NewVersionRequest(
                USER, newSession, newSlot, List.of(formal)));

        assertThat(result.savedContext()).extracting(Msg::getId).containsExactly("201");
        assertThat(result.savedContext()).noneMatch(m -> m.getRole() == MsgRole.TOOL);
        var newJson = store.get(USER, newSlot, WorkingContextStateAccess.AGENT_STATE_KEY, AgentState.class)
                .orElseThrow()
                .toJson();
        assertThat(newJson).isNotEqualTo(oldJson);
        assertThat(store.get(USER, SLOT, WorkingContextStateAccess.AGENT_STATE_KEY, AgentState.class))
                .isPresent();
    }

    @Test
    void compactionWithBinaryIsRejectedAndPriorKept() {
        seedPriorLocatorContext("101", "11");
        media.put(USER, SESSION, "11", IMAGE_A, "image/png");
        var priorVersion = store.version(USER, SLOT);
        var compacted = AgentState.builder()
                .userId(USER)
                .sessionId(SLOT)
                .context(List.of(Msg.builder()
                        .id("summary")
                        .role(MsgRole.USER)
                        .content(DataBlock.builder()
                                .id("11")
                                .source(new Base64Source(
                                        "image/png",
                                        java.util.Base64.getEncoder().encodeToString(IMAGE_A)))
                                .build())
                        .build()))
                .build();

        var save = useCase.saveCandidateContext(new WorkingContextRunUseCase.SaveRequest(
                USER, SESSION, SLOT, compacted, false));

        assertThat(save.saved()).isFalse();
        assertThat(store.version(USER, SLOT)).isEqualTo(priorVersion);
        assertThat(conversation.recoveryStatus(SESSION)).isEqualTo(SessionRecoveryStatus.READY);
    }

    @Test
    void audioVideoAndFileDoNotEnterPhaseOneModelContext() {
        media.put(USER, SESSION, "11", IMAGE_A, "image/png");
        media.put(USER, SESSION, "21", "audio".getBytes(StandardCharsets.UTF_8), "audio/mpeg");
        media.put(USER, SESSION, "22", "video".getBytes(StandardCharsets.UTF_8), "video/mp4");
        media.put(USER, SESSION, "23", "file".getBytes(StandardCharsets.UTF_8), "application/pdf");
        var message = new FormalMessage(
                "101",
                SESSION,
                USER,
                FormalSender.USER,
                List.of(
                        new FormalContentBlock.Text("mixed"),
                        new FormalContentBlock.Media("11", MediaKind.IMAGE),
                        new FormalContentBlock.Media("21", MediaKind.AUDIO),
                        new FormalContentBlock.Media("22", MediaKind.VIDEO),
                        new FormalContentBlock.Media("23", MediaKind.FILE)));

        var result = useCase.executeTurn(new WorkingContextRunUseCase.TurnRequest(
                USER, SESSION, SLOT, List.of(message), true));

        var blocks = result.savedContext().get(0).getContent();
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0)).isInstanceOf(TextBlock.class);
        assertThat(imageDataBlocks(result.savedContext().get(0))).extracting(DataBlock::getId).containsExactly("11");
        assertThat(result.modelRequestCopy().messages().get(0).getContent()).hasSize(2);
    }

    private FormalMessage userTextAndImage(String messageId, String mediaId) {
        return new FormalMessage(
                messageId,
                SESSION,
                USER,
                FormalSender.USER,
                List.of(
                        new FormalContentBlock.Text("hello"),
                        new FormalContentBlock.Media(mediaId, MediaKind.IMAGE)));
    }

    private void seedPriorLocatorContext(String messageId, String mediaId) {
        media.put(USER, SESSION, mediaId, IMAGE_A, "image/png");
        store.save(
                USER,
                SLOT,
                WorkingContextStateAccess.AGENT_STATE_KEY,
                AgentState.builder()
                        .userId(USER)
                        .sessionId(SLOT)
                        .context(List.of(assembledUser(messageId, "hello", mediaId)))
                        .build());
    }

    private static Msg assembledUser(String messageId, String text, String mediaId) {
        return Msg.builder()
                .id(messageId)
                .role(MsgRole.USER)
                .content(
                        TextBlock.builder().text(text).build(),
                        DataBlock.builder()
                                .id(mediaId)
                                .source(new URLSource("brain-media:" + mediaId, "image/png"))
                                .build())
                .build();
    }

    private static List<DataBlock> imageDataBlocks(Msg msg) {
        List<DataBlock> out = new ArrayList<>();
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof DataBlock data && data.getSource() != null) {
                out.add(data);
            }
        }
        return out;
    }

    private static void assertStableLocator(DataBlock block, String mediaId) {
        assertThat(block.getId()).isEqualTo(mediaId);
        assertThat(block.getSource()).isInstanceOf(URLSource.class);
        var url = ((URLSource) block.getSource()).getUrl();
        assertThat(url).isNotBlank();
        assertThat(url.toLowerCase()).doesNotStartWith("http://").doesNotStartWith("https://");
        assertThat(url).contains(mediaId);
    }

    private static void assertNoBinaryOrPresigned(DataBlock block) {
        Source source = block.getSource();
        assertThat(source).isNotInstanceOf(Base64Source.class);
        if (source instanceof URLSource url) {
            assertThat(url.getUrl().toLowerCase()).doesNotContain("x-amz-signature");
            assertThat(url.getUrl().toLowerCase()).doesNotStartWith("http");
        }
    }

    static final class RecordingModel implements ModelCaller {
        ModelResponse response;
        final AtomicReference<ModelRequestCopy> last = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();

        RecordingModel(ModelResponse response) {
            this.response = response;
        }

        @Override
        public ModelResponse call(ModelRequestCopy request) {
            calls.incrementAndGet();
            last.set(request);
            return response;
        }
    }

    static final class FakeConversation implements FormalConversationPort {
        private final Map<String, SessionRecoveryStatus> status = new LinkedHashMap<>();
        private final AtomicLong assistantIds = new AtomicLong(500);

        FakeConversation() {
            status.put(SESSION, SessionRecoveryStatus.READY);
        }

        @Override
        public SessionRecoveryStatus recoveryStatus(String sessionId) {
            return status.getOrDefault(sessionId, SessionRecoveryStatus.READY);
        }

        @Override
        public boolean acceptsNewRun(String sessionId) {
            return recoveryStatus(sessionId) == SessionRecoveryStatus.READY;
        }

        @Override
        public void markReconciling(String sessionId, String reason) {
            status.put(sessionId, SessionRecoveryStatus.RECONCILING);
        }

        @Override
        public void markReady(String sessionId) {
            status.put(sessionId, SessionRecoveryStatus.READY);
        }

        @Override
        public FormalMessage acceptAssistantMessage(
                String sessionId, String ownerUserId, List<FormalContentBlock> blocks) {
            return new FormalMessage(
                    String.valueOf(assistantIds.incrementAndGet()),
                    sessionId,
                    ownerUserId,
                    FormalSender.ASSISTANT,
                    blocks);
        }
    }

    static final class FakeMedia implements FormalMediaPort {
        private final Map<String, MediaOriginal> byKey = new LinkedHashMap<>();
        private final Map<String, String> ownerByMedia = new LinkedHashMap<>();
        private final Map<String, String> sessionByMedia = new LinkedHashMap<>();
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger persists = new AtomicInteger();
        private final AtomicInteger downloads = new AtomicInteger();
        private final AtomicLong nextId = new AtomicLong(900);
        private final Set<String> failReads = java.util.concurrent.ConcurrentHashMap.newKeySet();

        void put(String userId, String sessionId, String mediaId, byte[] bytes, String mime) {
            byKey.put(mediaId, new MediaOriginal(mediaId, mime, bytes, sha(bytes)));
            ownerByMedia.put(mediaId, userId);
            sessionByMedia.put(mediaId, sessionId);
        }

        void failReadFor(String mediaId) {
            failReads.add(mediaId);
        }

        int readCount() {
            return reads.get();
        }

        int persistCount() {
            return persists.get();
        }

        int downloadByUrlCount() {
            return downloads.get();
        }

        @Override
        public void requireSessionOwned(String userId, String sessionId, String mediaId) {
            if (!userId.equals(ownerByMedia.get(mediaId)) || !sessionId.equals(sessionByMedia.get(mediaId))) {
                throw new MediaUnavailableException("MEDIA_UNAVAILABLE");
            }
        }

        @Override
        public MediaOriginal readVerified(String userId, String sessionId, String mediaId) {
            reads.incrementAndGet();
            requireSessionOwned(userId, sessionId, mediaId);
            if (failReads.contains(mediaId) || !byKey.containsKey(mediaId)) {
                throw new MediaUnavailableException("MEDIA_UNAVAILABLE");
            }
            return byKey.get(mediaId);
        }

        @Override
        public BoundFormalMedia persistAssistantImage(
                String userId, String sessionId, String messageId, byte[] bytes, String mimeType) {
            persists.incrementAndGet();
            String id = String.valueOf(nextId.incrementAndGet());
            put(userId, sessionId, id, bytes, mimeType);
            return new BoundFormalMedia(id, messageId, MediaKind.IMAGE, mimeType);
        }
    }

    static final class InMemoryStateStore implements AgentStateStore {
        private final Map<String, AgentState> values = new LinkedHashMap<>();
        private final Map<String, Long> versions = new LinkedHashMap<>();
        private final Map<String, Boolean> corrupt = new LinkedHashMap<>();

        private static String key(String userId, String sessionId, String stateKey) {
            return userId + "|" + sessionId + "|" + stateKey;
        }

        long version(String userId, String sessionId) {
            return versions.getOrDefault(key(userId, sessionId, WorkingContextStateAccess.AGENT_STATE_KEY), 0L);
        }

        void corrupt(String userId, String sessionId) {
            corrupt.put(key(userId, sessionId, WorkingContextStateAccess.AGENT_STATE_KEY), true);
        }

        @Override
        public void save(String userId, String sessionId, String stateKey, State state) {
            String k = key(userId, sessionId, stateKey);
            values.put(k, AgentState.fromJsonString(((AgentState) state).toJson()));
            versions.put(k, versions.getOrDefault(k, 0L) + 1L);
            corrupt.put(k, false);
        }

        @Override
        public void save(String userId, String sessionId, String stateKey, List<? extends State> states) {
            if (!states.isEmpty()) {
                save(userId, sessionId, stateKey, states.get(states.size() - 1));
            }
        }

        @Override
        public <T extends State> Optional<T> get(String userId, String sessionId, String stateKey, Class<T> type) {
            String k = key(userId, sessionId, stateKey);
            if (Boolean.TRUE.equals(corrupt.get(k))) {
                return Optional.empty();
            }
            AgentState value = values.get(k);
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(type.cast(AgentState.fromJsonString(value.toJson())));
        }

        @Override
        public <T extends State> List<T> getList(String userId, String sessionId, String stateKey, Class<T> type) {
            return get(userId, sessionId, stateKey, type).map(List::of).orElseGet(List::of);
        }

        @Override
        public <T extends State> VersionedState<T> getVersioned(
                String userId, String sessionId, String stateKey, Class<T> type) {
            Optional<T> value = get(userId, sessionId, stateKey, type);
            if (value.isEmpty()) {
                return new VersionedState<>(null, 0L);
            }
            return new VersionedState<>(value.get(), version(userId, sessionId));
        }

        @Override
        public long saveIfVersion(
                String userId, String sessionId, String stateKey, State state, long expectedVersion) {
            String k = key(userId, sessionId, stateKey);
            long current = versions.getOrDefault(k, 0L);
            if (current != expectedVersion) {
                return AgentStateStore.UNVERSIONED;
            }
            save(userId, sessionId, stateKey, state);
            return versions.get(k);
        }

        @Override
        public boolean exists(String userId, String sessionId) {
            return values.keySet().stream().anyMatch(k -> k.startsWith(userId + "|" + sessionId + "|"));
        }

        @Override
        public void delete(String userId, String sessionId) {
            values.keySet().removeIf(k -> k.startsWith(userId + "|" + sessionId + "|"));
        }

        @Override
        public Set<String> listSessionIds(String userId) {
            return Set.of();
        }
    }

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
