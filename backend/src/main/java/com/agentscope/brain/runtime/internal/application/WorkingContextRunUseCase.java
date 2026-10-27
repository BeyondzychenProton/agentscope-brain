package com.agentscope.brain.runtime.internal.application;

import com.agentscope.brain.conversation.api.FormalContentBlock;
import com.agentscope.brain.conversation.api.FormalConversationPort;
import com.agentscope.brain.conversation.api.FormalMessage;
import com.agentscope.brain.conversation.api.SessionRecoveryStatus;
import com.agentscope.brain.media.api.BoundFormalMedia;
import com.agentscope.brain.media.api.FormalMediaPort;
import com.agentscope.brain.media.api.MediaKind;
import com.agentscope.brain.media.api.MediaOriginal;
import com.agentscope.brain.media.api.MediaUnavailableException;
import com.agentscope.brain.runtime.internal.domain.FormalDialogueAssembler;
import com.agentscope.brain.runtime.internal.domain.WorkingContextAligner;
import com.agentscope.brain.runtime.internal.domain.WorkingContextSaveGate;
import com.agentscope.brain.runtime.internal.integration.ModelCaller;
import com.agentscope.brain.runtime.internal.integration.ModelCaller.ModelRequestCopy;
import com.agentscope.brain.runtime.internal.integration.ModelCaller.ModelResponse;
import com.agentscope.brain.runtime.internal.integration.WorkingContextStateAccess;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.VersionedState;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * One Run working-context use case: assemble locators, gate saves, hydrate originals into a
 * request copy, and align after save failures.
 */
public final class WorkingContextRunUseCase {
    private final FormalConversationPort conversation;
    private final FormalMediaPort media;
    private final WorkingContextStateAccess state;
    private final ModelCaller model;
    private final FormalDialogueAssembler assembler = new FormalDialogueAssembler();
    private final WorkingContextSaveGate saveGate;
    private final WorkingContextAligner aligner;

    public WorkingContextRunUseCase(
            FormalConversationPort conversation,
            FormalMediaPort media,
            WorkingContextStateAccess state,
            ModelCaller model) {
        this.conversation = conversation;
        this.media = media;
        this.state = state;
        this.model = model;
        this.saveGate = new WorkingContextSaveGate(media);
        this.aligner = new WorkingContextAligner(media);
    }

    public TurnResult executeTurn(TurnRequest request) {
        if (!conversation.acceptsNewRun(request.sessionId())) {
            throw new NewRunBlockedException(request.sessionId());
        }
        AgentState usable;
        if (request.newFormalMessageSubmitted()) {
            AgentState candidate = AgentState.builder()
                    .userId(request.userId())
                    .sessionId(request.stateSlot())
                    .context(assembler.assemble(request.formalMessages()))
                    .build();
            SaveResult saved = writeIfAccepted(
                    request.userId(),
                    request.sessionId(),
                    request.stateSlot(),
                    candidate,
                    true);
            if (!saved.saved()) {
                return TurnResult.failed(
                        conversation.recoveryStatus(request.sessionId()),
                        readContext(request.userId(), request.stateSlot()),
                        null,
                        false,
                        "CONTEXT_SAVE_REJECTED");
            }
            usable = saved.state();
        } else {
            usable = state.read(request.userId(), request.stateSlot())
                    .map(VersionedState::value)
                    .orElse(null);
            if (usable == null) {
                return TurnResult.failed(
                        SessionRecoveryStatus.READY,
                        List.of(),
                        null,
                        false,
                        "CONTEXT_SAVE_REJECTED");
            }
        }
        try {
            ModelRequestCopy copy = hydrate(request.userId(), request.sessionId(), usable);
            ModelResponse response = model.call(copy);
            AgentState afterModel = applyModelResponse(
                    request.userId(), request.sessionId(), request.stateSlot(), usable, response);
            SaveResult afterSave = writeIfAccepted(
                    request.userId(),
                    request.sessionId(),
                    request.stateSlot(),
                    afterModel,
                    true);
            if (!afterSave.saved()) {
                return TurnResult.failed(
                        conversation.recoveryStatus(request.sessionId()),
                        readContext(request.userId(), request.stateSlot()),
                        copy,
                        true,
                        "CONTEXT_SAVE_REJECTED");
            }
            return TurnResult.ok(
                    conversation.recoveryStatus(request.sessionId()),
                    WorkingContextSaveGate.contextOf(afterSave.state()),
                    copy,
                    true);
        } catch (MediaUnavailableException ex) {
            return TurnResult.failed(
                    SessionRecoveryStatus.READY,
                    readContext(request.userId(), request.stateSlot()),
                    null,
                    false,
                    "MEDIA_UNAVAILABLE");
        }
    }

    public SaveResult saveCandidateContext(SaveRequest request) {
        return writeIfAccepted(
                request.userId(),
                request.sessionId(),
                request.stateSlot(),
                request.candidate(),
                request.newFormalMessageSubmitted());
    }

    public AlignResult alignBeforeNextRun(AlignRequest request) {
        Optional<VersionedState<AgentState>> prior = state.read(request.userId(), request.stateSlot());
        try {
            AgentState aligned = aligner.alignOrRebuild(
                    request.userId(),
                    request.sessionId(),
                    request.stateSlot(),
                    prior.map(VersionedState::value).orElse(null),
                    request.formalMessages());
            long expected = prior.map(VersionedState::version).orElse(0L);
            if (prior.isEmpty()) {
                expected = 0L;
            }
            if (!saveGate.accepts(request.userId(), request.sessionId(), aligned)) {
                conversation.markReconciling(request.sessionId(), "CONTEXT_ALIGN_FAILED");
                return new AlignResult(
                        SessionRecoveryStatus.RECONCILING, readContext(request.userId(), request.stateSlot()));
            }
            Optional<Long> written =
                    state.saveIfVersion(request.userId(), request.stateSlot(), aligned, expected);
            if (written.isEmpty() && prior.isEmpty()) {
                // First write into empty/corrupt slot: force save via expected 0; if store had corrupt empty, version may be 0.
                written = state.saveIfVersion(request.userId(), request.stateSlot(), aligned, 0L);
            }
            if (written.isEmpty()) {
                conversation.markReconciling(request.sessionId(), "CONTEXT_ALIGN_FAILED");
                return new AlignResult(
                        SessionRecoveryStatus.RECONCILING, readContext(request.userId(), request.stateSlot()));
            }
            conversation.markReady(request.sessionId());
            return new AlignResult(SessionRecoveryStatus.READY, WorkingContextSaveGate.contextOf(aligned));
        } catch (MediaUnavailableException ex) {
            conversation.markReconciling(request.sessionId(), "MEDIA_UNAVAILABLE");
            return new AlignResult(
                    SessionRecoveryStatus.RECONCILING, readContext(request.userId(), request.stateSlot()));
        }
    }

    public TurnResult openNewVersionSlot(NewVersionRequest request) {
        AgentState assembled = aligner.rebuild(request.userId(), request.stateSlot(), request.formalMessages());
        if (!saveGate.accepts(request.userId(), request.sessionId(), assembled)) {
            conversation.markReconciling(request.sessionId(), "CONTEXT_ALIGN_FAILED");
            return TurnResult.failed(
                    SessionRecoveryStatus.RECONCILING, List.of(), null, false, "MEDIA_UNAVAILABLE");
        }
        Optional<Long> written = state.saveIfVersion(request.userId(), request.stateSlot(), assembled, 0L);
        if (written.isEmpty()) {
            return TurnResult.failed(
                    conversation.recoveryStatus(request.sessionId()), List.of(), null, false, "CONTEXT_SAVE_REJECTED");
        }
        conversation.markReady(request.sessionId());
        return TurnResult.ok(
                SessionRecoveryStatus.READY, WorkingContextSaveGate.contextOf(assembled), null, false);
    }

    private SaveResult writeIfAccepted(
            String userId, String sessionId, String stateSlot, AgentState candidate, boolean newFormalSubmitted) {
        Optional<VersionedState<AgentState>> current = state.read(userId, stateSlot);
        long expected = current.map(VersionedState::version).orElse(0L);
        if (!saveGate.accepts(userId, sessionId, candidate)) {
            if (newFormalSubmitted) {
                conversation.markReconciling(sessionId, "CONTEXT_SAVE_FAILED");
            }
            return SaveResult.rejected(
                    conversation.recoveryStatus(sessionId),
                    current.map(VersionedState::value).orElse(null));
        }
        Optional<Long> written = state.saveIfVersion(userId, stateSlot, candidate, expected);
        if (written.isEmpty()) {
            if (newFormalSubmitted) {
                conversation.markReconciling(sessionId, "CONTEXT_SAVE_FAILED");
            }
            return SaveResult.rejected(
                    conversation.recoveryStatus(sessionId),
                    current.map(VersionedState::value).orElse(null));
        }
        return SaveResult.accepted(conversation.recoveryStatus(sessionId), candidate);
    }

    private ModelRequestCopy hydrate(String userId, String sessionId, AgentState saved) {
        List<Msg> copy = new ArrayList<>();
        for (Msg msg : WorkingContextSaveGate.contextOf(saved)) {
            List<ContentBlock> blocks = new ArrayList<>();
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof DataBlock data && data.getId() != null && !data.getId().isBlank()) {
                    MediaOriginal original = media.readVerified(userId, sessionId, data.getId());
                    blocks.add(DataBlock.builder()
                            .id(data.getId())
                            .name(data.getName())
                            .source(new Base64Source(
                                    original.mimeType(),
                                    Base64.getEncoder().encodeToString(original.bytes())))
                            .build());
                } else {
                    blocks.add(block);
                }
            }
            copy.add(Msg.builder()
                    .id(msg.getId())
                    .name(msg.getName())
                    .role(msg.getRole())
                    .content(blocks)
                    .metadata(msg.getMetadata())
                    .timestamp(msg.getTimestamp())
                    .build());
        }
        return new ModelRequestCopy(copy);
    }

    private AgentState applyModelResponse(
            String userId, String sessionId, String stateSlot, AgentState prior, ModelResponse response) {
        // http(s) image URLs from model/tool are intentionally ignored: never download, never persist.
        List<FormalContentBlock> assistantBlocks = new ArrayList<>();
        if (response.text() != null && !response.text().isBlank()) {
            assistantBlocks.add(new FormalContentBlock.Text(response.text()));
        }
        List<Msg> context = new ArrayList<>(WorkingContextSaveGate.contextOf(prior));
        if (response.imageBytesInBody().isEmpty()) {
            if (!assistantBlocks.isEmpty()) {
                FormalMessage accepted =
                        conversation.acceptAssistantMessage(sessionId, userId, assistantBlocks);
                context.add(assembler.toMsg(accepted));
            }
            return AgentState.builder()
                    .userId(userId)
                    .sessionId(stateSlot)
                    .context(context)
                    .build();
        }
        FormalMessage placeholder =
                conversation.acceptAssistantMessage(sessionId, userId, List.of(new FormalContentBlock.Text(
                        response.text() == null || response.text().isBlank() ? " " : response.text())));
        List<FormalContentBlock> boundBlocks = new ArrayList<>();
        if (response.text() != null && !response.text().isBlank()) {
            boundBlocks.add(new FormalContentBlock.Text(response.text()));
        }
        for (byte[] bytes : response.imageBytesInBody()) {
            BoundFormalMedia bound =
                    media.persistAssistantImage(userId, sessionId, placeholder.messageId(), bytes, "image/png");
            boundBlocks.add(new FormalContentBlock.Media(bound.mediaId(), MediaKind.IMAGE));
        }
        FormalMessage assistant = new FormalMessage(
                placeholder.messageId(), sessionId, userId, placeholder.sender(), boundBlocks);
        context.add(assembler.toMsg(assistant));
        return AgentState.builder()
                .userId(userId)
                .sessionId(stateSlot)
                .context(context)
                .build();
    }

    private List<Msg> readContext(String userId, String stateSlot) {
        return state.read(userId, stateSlot)
                .map(v -> WorkingContextSaveGate.contextOf(v.value()))
                .orElseGet(List::of);
    }

    public record TurnRequest(
            String userId,
            String sessionId,
            String stateSlot,
            List<FormalMessage> formalMessages,
            boolean newFormalMessageSubmitted) {
        public TurnRequest {
            formalMessages = formalMessages == null ? List.of() : List.copyOf(formalMessages);
        }
    }

    public record SaveRequest(
            String userId,
            String sessionId,
            String stateSlot,
            AgentState candidate,
            boolean newFormalMessageSubmitted) {}

    public record AlignRequest(
            String userId, String sessionId, String stateSlot, List<FormalMessage> formalMessages) {
        public AlignRequest {
            formalMessages = formalMessages == null ? List.of() : List.copyOf(formalMessages);
        }
    }

    public record NewVersionRequest(
            String userId, String sessionId, String stateSlot, List<FormalMessage> formalMessages) {
        public NewVersionRequest {
            formalMessages = formalMessages == null ? List.of() : List.copyOf(formalMessages);
        }
    }

    public record TurnResult(
            SessionRecoveryStatus sessionStatus,
            List<Msg> savedContext,
            ModelRequestCopy modelRequestCopy,
            boolean modelCalled,
            String failureCode) {
        public TurnResult {
            savedContext = savedContext == null ? List.of() : List.copyOf(savedContext);
        }

        static TurnResult ok(
                SessionRecoveryStatus status, List<Msg> context, ModelRequestCopy copy, boolean called) {
            return new TurnResult(status, context, copy, called, null);
        }

        static TurnResult failed(
                SessionRecoveryStatus status,
                List<Msg> context,
                ModelRequestCopy copy,
                boolean called,
                String code) {
            return new TurnResult(status, context, copy, called, code);
        }
    }

    public record SaveResult(boolean saved, SessionRecoveryStatus sessionStatus, AgentState state) {
        static SaveResult accepted(SessionRecoveryStatus status, AgentState state) {
            return new SaveResult(true, status, state);
        }

        static SaveResult rejected(SessionRecoveryStatus status, AgentState prior) {
            return new SaveResult(false, status, prior);
        }
    }

    public record AlignResult(SessionRecoveryStatus sessionStatus, List<Msg> savedContext) {
        public AlignResult {
            savedContext = savedContext == null ? List.of() : List.copyOf(savedContext);
        }
    }

    public static final class NewRunBlockedException extends RuntimeException {
        public NewRunBlockedException(String sessionId) {
            super("New Run blocked for session " + sessionId);
        }
    }
}
