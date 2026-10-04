package gold.debug.windowstolinux.app.service.ai;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.ai.model.AiCapabilityCheck;
import gold.debug.windowstolinux.app.ai.model.AiCapabilityStatus;
import gold.debug.windowstolinux.app.ai.model.AiCapabilityType;
import gold.debug.windowstolinux.app.ai.model.AiPurposeAssignment;
import gold.debug.windowstolinux.app.ai.model.AiPurposeType;
import gold.debug.windowstolinux.app.ai.model.ModelCapabilityProbe;
import gold.debug.windowstolinux.app.ai.vision.VisionCapabilityProbe;
import gold.debug.windowstolinux.app.db.PlatformStorage;
import gold.debug.windowstolinux.app.db.ai.StoredAiModelInfo;
import gold.debug.windowstolinux.app.service.ai.AiModelInfo;
import gold.debug.windowstolinux.app.service.ai.AiModelListAccess;
import gold.debug.windowstolinux.app.service.ai.AiModelProfile;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.app.service.task.*;
import gold.debug.windowstolinux.appapi.ai.voice.VoiceOptions;
import gold.debug.windowstolinux.appapi.ai.voice.VoiceRequest;
import gold.debug.windowstolinux.appapi.ai.voice.VoiceResponse;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.recover.RecoveryRegistration;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;

/** Owns verified model configuration and explicit purpose selection. / 持有已验证模型配置及明确用途选择。 */
public final class PlatformModelService implements AiModelListAccess, AutoCloseable {
    /** Stable host probe protocol without business actions. / 不含业务动作的稳定宿主探测协议。 */
    private static final String PROBE_TYPE = "platform.ai.probe.v1";

    /** Actual platform repositories. / 实际平台仓库。 */
    private final PlatformStorage persistence;

    /** Current-user and original-task credentials. / 当前用户及原任务凭据。 */
    private final DesktopSecurityService security;

    /** Central task coordinator. / 中央任务协调器。 */
    private final PlatformTaskRuntime runtime;

    /** Host version task binding. / 宿主版本任务绑定。 */
    private final TaskService tasks;

    /** Real transport and retained physical cleanup. / 真实传输及保留物理清理。 */
    private final PlatformAiService ai;

    /** Original-version recovery registrations. / 原版本恢复登记。 */
    private final List<RecoveryRegistration> recoveries = new ArrayList<>();

    /** Current-process cancellation handles. / 当前进程取消句柄。 */
    private final Map<UUID, TaskHandle<Void>> handles = new ConcurrentHashMap<>();

    /** Seals new settings and probe admission after teardown. / 拆除后封闭新设置和探测准入。 */
    private volatile boolean closed;

    /** Binds settings, probes and interruption recovery. / 绑定设置、探测及中断恢复。
     * @param persistence current storage / 当前存储
     * @param security current credential authority / 当前凭据授权方
     * @param runtime central tasks / 中央任务
     * @param version actual application version / 实际应用版本
     * @param ai host-owned transport / 宿主持有的传输
     * @throws ApiException if recovery registration fails / 恢复登记失败时 */
    public PlatformModelService(PlatformStorage persistence, DesktopSecurityService security,
                                PlatformTaskRuntime runtime, String version, PlatformAiService ai) throws ApiException {
        this.persistence = Objects.requireNonNull(persistence);
        this.security = Objects.requireNonNull(security);
        this.runtime = Objects.requireNonNull(runtime);
        this.ai = Objects.requireNonNull(ai);
        tasks = runtime.bind(PlatformTaskAdmission.PLATFORM_OWNER, version, false);
        var versions = new LinkedHashSet<String>();
        versions.add(version);
        checks().forEach(task -> versions.add(task.ownerVersion()));
        try {
            for (var original : versions)
                recoveries.add(runtime.bind(PlatformTaskAdmission.PLATFORM_OWNER, original, true)
                    .registerRecovery(PROBE_TYPE, (previous, task) -> {
                        if (!HostTaskRecovery.releasePreviousProcess(persistence.database(), previous,
                            Map.of("CONNECTION", "ai-", "CUSTOM", "ai-access-")))
                            ai.closeTaskResources(task);
                        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(
                            ProblemCode.UNKNOWN, "MODEL_PROBE_INTERRUPTED",
                            "The interrupted probe is closed without replay; reload saved configuration before a new probe",
                            Map.of(), Optional.empty())));
                    }));
        } catch (ApiException failure) {
            try {
                close();
            } catch (ApiException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** Lists non-secret revision-bound model evidence. / 列出非秘密且绑定修订的模型证据。
     * @return ordered inventory / 有序清单
     * @throws SQLException if storage is unavailable / 存储不可用时 */
    @Override
    public List<AiModelInfo> listAiConfigurations() throws SQLException {
        settings();
        return persistence.aiProfiles().listConfigured().stream()
            .map(value -> new AiModelInfo(AiModelProfile.fromStored(value.profile()), value.name(),
                value.priority(), value.revision(), value.textVerifiedAt(), value.visionVerifiedAt(),
                value.fileVoiceVerifiedAt(), value.realtimeVoiceVerifiedAt(), value.checks()))
            .toList();
    }

    /** Reads the exact configured output ceiling. / 读取精确已配置输出上限。
     * @param id model identity / 模型身份
     * @return configured ceiling or absent / 已配置上限或缺失
     * @throws SQLException if storage is unavailable / 存储不可用时 */
    public OptionalInt outputLimit(String id) throws SQLException {
        settings();
        return persistence.aiProfiles().outputLimit(id);
    }

    /** Reads saved purpose membership without enrolling models. / 读取已保存用途成员，不自动纳入模型。
     * @param purpose selected purpose / 所选用途
     * @return ordered members / 有序成员
     * @throws SQLException if storage is unavailable / 存储不可用时 */
    @Override
    public List<AiPurposeAssignment> listAiPurpose(AiPurposeType purpose) throws SQLException {
        settings();
        return persistence.aiProfiles().purposes().list(purpose);
    }

    /** Saves independently ordered purpose membership. / 保存独立排序的用途成员。
     * @param purpose selected purpose / 所选用途
     * @param assignments complete ordered draft / 完整有序草稿
     * @throws SQLException if validation or storage fails / 校验或存储失败时 */
    @Override
    public void saveAiPurpose(AiPurposeType purpose, List<AiPurposeAssignment> assignments) throws SQLException {
        settings();
        try (var operation = security.enterOperation()) {
            persistence.aiProfiles().purposes().save(purpose, assignments);
        }
    }

    /** Reorders the inventory without changing purpose priority. / 调整清单顺序，不改变用途优先级。
     * @param ids complete inventory order / 完整清单顺序
     * @throws SQLException if storage fails / 存储失败时 */
    @Override
    public void reorderAiProviders(List<String> ids) throws SQLException {
        settings();
        try (var operation = security.enterOperation()) {
            persistence.aiProfiles().reorder(ids);
        }
    }

    /** Saves explicit configuration without contacting a model or choosing a purpose. / 保存明确配置，不联系模型或选择用途。
     * @param profile reviewed protocol settings / 已审阅协议设置
     * @param name display label / 显示名称
     * @param suppliedKey replacement key or empty to retain, always cleared / 替换密钥或空以保留，始终清零
     * @param outputLimit positive text output ceiling / 正的文字输出上限
     * @throws Exception if validation or atomic protected storage fails / 校验或受保护原子存储失败时 */
    public void save(AiModelProfile profile, String name, char[] suppliedKey, int outputLimit) throws Exception {
        Objects.requireNonNull(suppliedKey);
        try {
            settings();
            if (name == null || name.isBlank() || name.length() > 120 || name.chars().anyMatch(Character::isISOControl)
                || outputLimit < 1) throw new IllegalArgumentException("Invalid model settings");
            try (var operation = security.enterOperation(); var store = security.openCredentials()) {
                store.transaction(() -> {
                    var previous = current(profile.id());
                    if (previous.isPresent() && !previous.orElseThrow().profile().credentialKey().equals(profile.credentialKey()))
                        throw new SQLException("The credential reference changed; reload settings", "40001");
                    if (previous.isEmpty() && suppliedKey.length == 0)
                        throw new IllegalArgumentException("An API key is required");
                    String credential = previous.map(v -> v.profile().credentialKey())
                        .orElseGet(() -> "ai/provider/" + profile.id() + "/api-key/" + UUID.randomUUID());
                    if (suppliedKey.length != 0) store.save(credential, suppliedKey);
                    var candidate = new AiModelProfile(profile.id(), profile.chatCompletionsEndpoint(), profile.model(),
                        credential, profile.fileTranscriptionEndpoint(), profile.realtimeTranscriptionEndpoint());
                    persistence.aiProfiles().saveConfiguration(candidate.stored(), name);
                    persistence.aiProfiles().saveOutputLimit(profile.id(), outputLimit);
                });
            }
        } finally {
            Arrays.fill(suppliedKey, '\0');
        }
    }

    /** Verifies one saved capability using its exact configuration revision. / 使用精确配置修订验证一项已保存能力。
     * @param id saved model identity / 已存模型身份
     * @param capability independently selected capability / 独立所选能力
     * @param audio actual WAV input for voice or null for text / 语音实际WAV输入，文字为空
     * @param expected reviewed expected transcript for voice / 已审阅语音预期文字
     * @return centrally owned original check / 中央持有的原检查
     * @throws Exception if admission or credential capture fails / 准入或凭据捕获失败时 */
    public TaskHandle<Void> verify(String id, AiCapabilityType capability, VoiceRequest audio, String expected)
        throws Exception {
        settings();
        Objects.requireNonNull(capability);
        var observed = current(id).orElseThrow(() -> new IllegalArgumentException("Save the model before verification"));
        boolean voice = capability == AiCapabilityType.VOICE_FILE || capability == AiCapabilityType.VOICE_REALTIME;
        if (voice && (audio == null || expected == null || expected.isBlank() || expected.length() > 4096))
            throw new IllegalArgumentException("Voice verification requires audio and expected text");
        var profile = observed.profile();
        String endpoint = switch (capability) {
            case TEXT, VISION -> profile.endpoint();
            case VOICE_FILE -> profile.fileEndpoint();
            case VOICE_REALTIME -> profile.realtimeEndpoint();
        };
        if (endpoint.isEmpty()) throw new IllegalArgumentException("This protocol address is not configured");
        int ceiling = persistence.aiProfiles().outputLimit(id).orElseThrow();
        char[] key;
        try (var operation = security.enterOperation(); var store = security.openCredentials()) {
            key = store.read(profile.credentialKey())
                .orElseThrow(() -> new IllegalArgumentException("The saved API key is unavailable"));
        }
        boolean submitted = false;
        try {
            var handle = tasks.<Void>submit(new TaskRequest(PROBE_TYPE, "Model: " + observed.name(), List.of(), true),
                task -> {
                    try {
                        OperationResult<Void> result = probe(observed, capability, audio, expected, ceiling, key, task);
                        if (result.outcome() == TaskResult.CANCELLED) return result;
                        var check = classify(result);
                        try {
                            persistence.aiProfiles().recordVerification(profile, observed.revision(), capability, check);
                        } catch (SQLException conflict) {
                            if ("40001".equals(conflict.getSQLState()))
                                return failed(ProblemCode.BUSY, "MODEL_PROBE_STALE", "Model settings changed during verification; reload before editing");
                            runtime.reportUncertainEffect(task);
                            return failed(ProblemCode.UNKNOWN, "MODEL_SAVE_UNKNOWN", "The verification commit must be reconciled before continuing");
                        }
                        return result;
                    } finally {
                        Arrays.fill(key, '\0');
                    }
                });
            submitted = true;
            handles.put(handle.id(), handle);
            handle.completion().whenComplete((result, failure) -> {
                Arrays.fill(key, '\0');
                handles.remove(handle.id());
            });
            return handle;
        } finally {
            if (!submitted) Arrays.fill(key, '\0');
        }
    }

    /** Sends only the explicitly selected protocol and checks its actual output. / 仅发送明确所选协议并核对实际输出。
     * @param observed frozen configuration / 冻结配置
     * @param capability selected protocol / 所选协议
     * @param audio reviewed WAV input / 已审阅WAV输入
     * @param expected expected transcript / 预期文字
     * @param ceiling text output bound / 文字输出上限
     * @param key captured protected key / 已捕获受保护密钥
     * @param task original executing task / 原执行任务
     * @return actual classified outcome / 实际分类结果 */
    private OperationResult<Void> probe(StoredAiModelInfo observed, AiCapabilityType capability,
                                        VoiceRequest audio, String expected, int ceiling, char[] key, TaskContext task) {
        try {
            if (capability == AiCapabilityType.TEXT || capability == AiCapabilityType.VISION) {
                ModelCapabilityProbe challenge;
                if (capability == AiCapabilityType.VISION) {
                    var visual = VisionCapabilityProbe.create(ceiling);
                    challenge = new ModelCapabilityProbe(visual.request(), visual.expected());
                } else {
                    challenge = ModelCapabilityProbe.create(ceiling);
                }
                var result = ai.probe(observed.profile(), key, challenge.request(), task);
                if (result.outcome() != TaskResult.SUCCEEDED)
                    return new OperationResult<>(result.outcome(), Optional.empty(), result.problem());
                if (!challenge.accepts(result.value().orElseThrow()))
                    return failed(ProblemCode.CONTRACT_VIOLATION, "MODEL_PROBE_ANSWER", "The model did not answer the generated capability challenge");
            } else {
                OperationResult<VoiceResponse> result;
                if (capability == AiCapabilityType.VOICE_FILE)
                    result = ai.probeVoice(observed.profile(), key, audio, task);
                else {
                    byte[] pcm = audio.pcm();
                    try {
                        result = ai.probeRealtime(observed.profile(), key,
                            new VoiceOptions(audio.timeout(), 1_048_576), pcm, task);
                    } finally {
                        Arrays.fill(pcm, (byte) 0);
                    }
                }
                if (result.outcome() != TaskResult.SUCCEEDED)
                    return new OperationResult<>(result.outcome(), Optional.empty(), result.problem());
                if (!normalized(expected).equalsIgnoreCase(normalized(result.value().orElseThrow().text())))
                    return failed(ProblemCode.CONTRACT_VIOLATION, "MODEL_PROBE_ANSWER", "The transcript did not match the reviewed expected text");
            }
            return task.cancelToken().requested() ? new OperationResult<>(TaskResult.CANCELLED, Optional.empty(), Optional.empty())
                : new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
        } catch (ApiException failure) {
            return new OperationResult<>(failure.problem().code() == ProblemCode.UNKNOWN ? TaskResult.UNKNOWN : TaskResult.FAILED,
                Optional.empty(), Optional.of(failure.problem()));
        } catch (java.io.IOException failure) {
            return failed(ProblemCode.IO_FAILURE, "MODEL_PROBE_INPUT", "The local capability challenge could not be generated");
        }
    }

    /** Normalizes harmless transcript whitespace. / 规范化无害的转写空白。
     * @param value transcript text / 转写文字
     * @return normalized text / 规范文字 */
    private static String normalized(String value) {
        return value.strip().replaceAll("\\s+", " ");
    }

    /** Stores fixed safe reason keys, never provider bodies or transcripts. / 保存固定安全原因键，不保存提供方正文或转写。
     * @param result actual probe outcome / 实际验证结果
     * @return independent safe evidence / 独立安全证据 */
    private static AiCapabilityCheck classify(OperationResult<Void> result) {
        if (result.outcome() == TaskResult.SUCCEEDED)
            return new AiCapabilityCheck(AiCapabilityStatus.AVAILABLE, "", Optional.of(Instant.now()));
        var problem = result.problem().orElseThrow();
        boolean unsupported = problem.code() == ProblemCode.INCOMPATIBLE || problem.eventCode().equals("VOICE_UNSUPPORTED");
        String reason = unsupported ? "MODEL_PROBE_UNSUPPORTED" : switch (problem.code()) {
            case ACCESS_DENIED -> "MODEL_PROBE_AUTH";
            case CONTRACT_VIOLATION, INVALID_INPUT -> "MODEL_PROBE_PROTOCOL";
            case UNKNOWN -> "MODEL_PROBE_UNKNOWN";
            default -> "MODEL_PROBE_NETWORK";
        };
        if (problem.eventCode().equals("MODEL_PROBE_ANSWER")) reason = "MODEL_PROBE_ANSWER";
        return new AiCapabilityCheck(unsupported ? AiCapabilityStatus.UNSUPPORTED : AiCapabilityStatus.FAILED,
            reason, Optional.of(Instant.now()));
    }

    /** Reads one authoritative revision. / 读取一个权威修订。
     * @param id exact model / 精确模型
     * @return current row or absent / 当前行或缺失
     * @throws SQLException if storage fails / 存储失败时 */
    private Optional<StoredAiModelInfo> current(String id) throws SQLException {
        return persistence.aiProfiles().listConfigured().stream().filter(value -> value.profile().id().equals(id))
            .findFirst();
    }

    /** Lists unfinished tasks owned by this stable probe protocol. / 列出此稳定探测协议的未结束任务。
     * @return retained checks / 保留检查 */
    public List<TaskSnapshot> checks() {
        return runtime.snapshots().stream().filter(task -> task.ownerId().equals(PlatformTaskAdmission.PLATFORM_OWNER)
            && task.type().equals(PROBE_TYPE) && task.state() != TaskState.ENDED).toList();
    }

    /** Requests cancellation of the exact current-process probe. / 请求取消精确的当前进程探测。
     * @param id original task identity / 原任务身份
     * @throws ApiException if no current handle exists / 无当前句柄时 */
    public void cancel(UUID id) throws ApiException {
        var handle = handles.get(id);
        if (handle == null)
            throw new ApiException(new Problem(ProblemCode.NOT_FOUND, "MODEL_PROBE_HANDLE",
                "No active probe handle exists", Map.of(), Optional.empty()));
        handle.requestCancel();
    }

    /** Reconciles only the original interrupted probe without resending. / 仅核对原中断探测，不重新发送。
     * @param id original task identity / 原任务身份
     * @return final reconciliation state / 最终核对状态
     * @throws ApiException if identity or current state forbids reconciliation / 身份或当前状态禁止核对时 */
    public CompletionStage<TaskSnapshot> reconcile(UUID id) throws ApiException {
        if (checks().stream().noneMatch(task -> task.id().equals(id)))
            throw new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "MODEL_PROBE_IDENTITY",
                "Not a retained model probe", Map.of(), Optional.empty()));
        return runtime.reconcile(id);
    }

    /** Seals new operations and releases recovery registrations. / 封闭新操作并释放恢复登记。
     * @throws ApiException if a registration is still occupied / 登记仍被占用时 */
    @Override
    public void close() throws ApiException {
        closed = true;
        for (var registration : List.copyOf(recoveries)) {
            registration.close();
            recoveries.remove(registration);
        }
    }

    /** Requires an unlocked current user on a background thread. / 要求后台线程上的已解锁当前用户。 */
    private void settings() {
        if (closed || SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Model settings are unavailable on this thread");
        security.requireUnlocked();
    }

    /** Constructs safe diagnostics without provider data. / 构造不含提供者数据的安全诊断。
     * @param code public category / 公开类别
     * @param event stable event / 稳定事件
     * @param message non-secret explanation / 非秘密说明
     * @return failed operation / 失败操作 */
    private static OperationResult<Void> failed(ProblemCode code, String event, String message) {
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(),
            Optional.of(new Problem(code, event, message, Map.of(), Optional.empty())));
    }
}
