package gold.debug.windowstolinux.app.service.ai;

import java.util.*;

import gold.debug.windowstolinux.app.db.ai.StoredAiModelProfile;
import gold.debug.windowstolinux.app.db.ai.AiModelStore;
import gold.debug.windowstolinux.app.db.ai.AiReasoningStore;
import gold.debug.windowstolinux.app.config.AppConfig;
import gold.debug.windowstolinux.appapi.ai.model.*;

/** Task-owned provider state, inaccessible through public diagnostic values. */
final class NativeModelContinuations {
    /** Keeps the original model capability ownership for State. / 为 State 保持原模型能力归属。
     * @param task authentic original task context / 真实原任务上下文
     * @param purpose declared invocation purpose / 声明的调用用途
     * @param profile captured non-secret connection configuration / 已捕获非秘密连接配置
     * @param revision captured configuration revision or revision source / 已捕获配置修订或修订来源
     * @param value captured value at this validated model boundary / 此已校验模型边界捕获的 value
     * @param payloadBytes captured payloadBytes at this validated model boundary / 此已校验模型边界捕获的 payloadBytes
     * @param reasoning captured reasoning control or settings owner / 捕获的推理控制或设置所有者
     */
    private record State(UUID task, AiPurpose purpose, StoredAiModelProfile profile, String revision, String value,
                         int payloadBytes, AiReasoningMode reasoning) {
    }

    private final AppConfig.NativeState limits;

    NativeModelContinuations(AppConfig.NativeState limits) {
        this.limits = Objects.requireNonNull(limits);
    }

    private final Map<UUID, State> states = new LinkedHashMap<>();

    private record Session(UUID task, AiPurpose purpose) {
    }

    private final Map<Session, State> bindings = new HashMap<>();

    private final Set<Session> stopped = new HashSet<>();

    /** Resolves this request override or its original purpose preference. / 解析本次请求覆写或原用途偏好。
     * @param profile captured non-secret connection configuration / 已捕获非秘密连接配置
     * @param request captured request at this validated model boundary / 此已校验模型边界捕获的 request
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    private static AiReasoningMode effective(StoredAiModelProfile profile, AiRequest request) {
        return request.reasoningOverride().orElseGet(() -> profile.reasoningDefaults().getOrDefault(request.purpose(), AiReasoningStore.legacyReasoning(profile.reasoningEffort())));
    }

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
        if (pinned != null && pinned.reasoning() != effective(pinned.profile(), request))
            throw new IllegalArgumentException("Native session reasoning changed");
        return Optional.ofNullable(profile);
    }

    synchronized Map<UUID, String> values(UUID task, AiRequest request, StoredAiModelProfile profile, String revision) {
        var result = new HashMap<UUID, String>();
        for (var message : request.messages())
            if (message.continuation().isPresent()) {
                var handle = message.continuation().orElseThrow();
                var state = require(handle, task, request.purpose(), revision);
                if (!state.profile().equals(profile) || state.reasoning() != effective(profile, request))
                    throw new IllegalArgumentException("Native continuation model changed");
                result.put(handle.id(), state.value());
            }
        return Map.copyOf(result);
    }

    synchronized AiContinuation retain(UUID task, AiPurpose purpose, StoredAiModelProfile profile, String revision, String value) {
        return retain(task, purpose, profile, revision, value, profile.reasoningDefaults().getOrDefault(purpose, AiReasoningStore.legacyReasoning(profile.reasoningEffort())));
    }

    /** Retains a bounded task-owned native state and reasoning identity. / 保留有界任务所属原生状态与推理身份。
     * @param task authentic original task context / 真实原任务上下文
     * @param purpose declared invocation purpose / 声明的调用用途
     * @param profile captured non-secret connection configuration / 已捕获非秘密连接配置
     * @param revision captured configuration revision or revision source / 已捕获配置修订或修订来源
     * @param value captured value at this validated model boundary / 此已校验模型边界捕获的 value
     * @param reasoning captured reasoning control or settings owner / 捕获的推理控制或设置所有者
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    synchronized AiContinuation retain(UUID task, AiPurpose purpose, StoredAiModelProfile profile, String revision, String value, AiReasoningMode reasoning) {
        if (value == null || value.isEmpty())
            throw new IllegalArgumentException("Native provider state exceeds its bound");
        int payloadBytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (payloadBytes > limits.maxValueBytes())
            throw new IllegalArgumentException("Native provider state exceeds its bound");
        long count = states.values().stream().filter(s -> s.task().equals(task)).count();
        long bytes = states.values().stream().filter(s -> s.task().equals(task)).mapToLong(State::payloadBytes).sum();
        if (count >= limits.maxStates() || bytes > limits.maxTotalBytes() - payloadBytes)
            throw new IllegalArgumentException("Native task state capacity exhausted");
        UUID id = UUID.randomUUID();
        states.put(id, new State(task, purpose, profile, revision, value, payloadBytes, reasoning));
        bindings.putIfAbsent(new Session(task, purpose), new State(task, purpose, profile, revision, "", 0, reasoning));
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

    synchronized void stopAfterRetentionFailure(UUID task, AiPurpose purpose) {
        stopped.add(new Session(task, purpose));
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
