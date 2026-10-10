package gold.debug.windowstolinux.app.service.ai;

import java.util.*;

import gold.debug.windowstolinux.app.db.ai.StoredAiModelProfile;
import gold.debug.windowstolinux.appapi.ai.model.*;

/** Task-owned provider state, inaccessible through public diagnostic values. */
final class NativeModelContinuations {
    private record State(UUID task, AiPurpose purpose, StoredAiModelProfile profile, String revision, String value) {
        int payloadBytes() {
            return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
    }

    private final Map<UUID, State> states = new LinkedHashMap<>();

    private record Session(UUID task, AiPurpose purpose) {
    }

    private final Map<Session, State> bindings = new HashMap<>();

    private final Set<Session> stopped = new HashSet<>();

    synchronized Optional<StoredAiModelProfile> bound(UUID task, AiRequest request, String revision) {
        if (stopped.contains(new Session(task, request.purpose())))
            throw new IllegalArgumentException("Native task session has stopped");
        var pinned = bindings.get(new Session(task, request.purpose()));
        if (pinned != null && !pinned.revision().equals(revision))
            throw new IllegalArgumentException("Native task revision changed");
        StoredAiModelProfile profile = pinned == null ? null : pinned.profile();
        for (var message : request.messages())
            if (message.continuation().isPresent()) {
                var state = require(message.continuation().orElseThrow(), task, request.purpose(), revision);
                if (profile != null && !profile.equals(state.profile()))
                    throw new IllegalArgumentException("Native history crosses model bindings");
                profile = state.profile();
            }
        return Optional.ofNullable(profile);
    }

    synchronized Map<UUID, String> values(UUID task, AiRequest request, StoredAiModelProfile profile, String revision) {
        var result = new HashMap<UUID, String>();
        for (var message : request.messages())
            if (message.continuation().isPresent()) {
                var handle = message.continuation().orElseThrow();
                var state = require(handle, task, request.purpose(), revision);
                if (!state.profile().equals(profile))
                    throw new IllegalArgumentException("Native continuation model changed");
                result.put(handle.id(), state.value());
            }
        return Map.copyOf(result);
    }

    synchronized AiContinuation retain(UUID task, AiPurpose purpose, StoredAiModelProfile profile, String revision, String value) {
        if (value == null || value.isEmpty() || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024 * 1024)
            throw new IllegalArgumentException("Native provider state exceeds its bound");
        long count = states.values().stream().filter(s -> s.task().equals(task)).count();
        long bytes = states.values().stream().filter(s -> s.task().equals(task)).mapToLong(State::payloadBytes).sum();
        if (count >= 160 || bytes + value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16L * 1024 * 1024)
            throw new IllegalArgumentException("Native task state capacity exhausted");
        UUID id = UUID.randomUUID();
        states.put(id, new State(task, purpose, profile, revision, value));
        bindings.putIfAbsent(new Session(task, purpose), new State(task, purpose, profile, revision, ""));
        return new AiContinuation(id, states.get(id).payloadBytes());
    }

    private State require(AiContinuation handle, UUID task, AiPurpose purpose, String revision) {
        var state = states.get(handle.id());
        if (state == null || !state.task().equals(task) || state.purpose() != purpose || !state.revision().equals(revision)
            || handle.payloadBytes() != state.payloadBytes())
            throw new IllegalArgumentException("Native continuation identity or revision changed");
        return state;
    }

    synchronized void stop(UUID task, AiPurpose purpose) {
        var session = new Session(task, purpose);
        if (bindings.containsKey(session)) stopped.add(session);
    }

    synchronized void clear(UUID task) {
        states.values().removeIf(s -> s.task().equals(task));
        bindings.keySet().removeIf(s -> s.task().equals(task));
        stopped.removeIf(s -> s.task().equals(task));
    }

    synchronized void clear() {
        states.clear();
        bindings.clear();
        stopped.clear();
    }
}
