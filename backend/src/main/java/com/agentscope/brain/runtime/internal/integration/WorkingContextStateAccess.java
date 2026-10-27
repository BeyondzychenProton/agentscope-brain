package com.agentscope.brain.runtime.internal.integration;

import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.VersionedState;
import java.util.Optional;

/** CAS access to the AgentScope working-context slot for one Run. */
public final class WorkingContextStateAccess {
    public static final String AGENT_STATE_KEY = "agent_state";

    private final AgentStateStore store;

    public WorkingContextStateAccess(AgentStateStore store) {
        this.store = store;
    }

    public Optional<VersionedState<AgentState>> read(String userId, String stateSlot) {
        VersionedState<AgentState> versioned =
                store.getVersioned(userId, stateSlot, AGENT_STATE_KEY, AgentState.class);
        if (versioned == null || !versioned.isPresent()) {
            return Optional.empty();
        }
        return Optional.of(versioned);
    }

    /** @return new version, or empty when CAS rejected / store reported no write */
    public Optional<Long> saveIfVersion(String userId, String stateSlot, AgentState state, long expectedVersion) {
        long written = store.saveIfVersion(userId, stateSlot, AGENT_STATE_KEY, state, expectedVersion);
        if (written == AgentStateStore.UNVERSIONED || written <= 0) {
            return Optional.empty();
        }
        return Optional.of(written);
    }
}
