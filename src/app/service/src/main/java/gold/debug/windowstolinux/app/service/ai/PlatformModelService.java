package gold.debug.windowstolinux.app.service.ai;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.ai.execution.protocol.ModelCapabilityProbe;
import gold.debug.windowstolinux.app.db.PlatformPersistence;
import gold.debug.windowstolinux.app.db.entity.StoredAiProviderConfiguration;
import gold.debug.windowstolinux.app.service.contract.profile.AiProviderProfile;
import gold.debug.windowstolinux.app.service.contract.result.AiProviderSummary;
import gold.debug.windowstolinux.app.service.contract.spi.AiInventoryAccess;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.app.service.task.*;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.app.ai.catalog.*;

/** Owns verified model configuration and explicit purpose selection. / 持有已验证模型配置及明确用途选择。 */
public final class PlatformModelService implements AiInventoryAccess, AutoCloseable {
    /** Stable host probe protocol without business actions. / 不含业务动作的稳定宿主探测协议。 */
    private static final String PROBE_TYPE = "platform.ai.probe.v1";

    /** Actual platform repositories. / 实际平台仓库。 */
    private final PlatformPersistence persistence;

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
    public PlatformModelService(PlatformPersistence persistence, DesktopSecurityService security,
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
                                    Map.of("CONNECTION", "ai-http-", "CUSTOM", "ai-access-")))
                                ai.closeTaskResources(task);
                            return new OperationResult<>(Outcome.FAILED, Optional.empty(), Optional.of(new Problem(
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
    public List<AiProviderSummary> listAiConfigurations() throws SQLException {
        settings();
        return persistence.aiProfiles().listConfigured().stream()
                .map(value -> new AiProviderSummary(AiProviderProfile.fromStored(value.profile()), value.name(),
                        value.priority(), value.revision(), value.textVerifiedAt(), value.visionVerifiedAt()))
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

    /** Submits a fresh probe and saves only its unchanged, verified configuration. / 提交全新探测，仅保存未变化且已验证的配置。
     * @param profile edited model metadata / 已编辑模型元数据
     * @param name display label / 显示名称
     * @param suppliedKey replacement or empty to retain the existing key; always cleared / 替换凭据或空以保留原凭据；始终清零
     * @param capability independent capability to verify / 要验证的独立能力
     * @param outputLimit explicit positive ceiling / 明确的正上限
     * @return original probe task / 原探测任务
     * @throws Exception if admission, configuration or credential capture fails / 准入、配置或凭据捕获失败时 */
    public TaskHandle<Void> verifyAndSave(AiProviderProfile profile, String name, char[] suppliedKey,
            AiCapabilityType capability, int outputLimit) throws Exception {
        Objects.requireNonNull(suppliedKey);
        char[][] captured = new char[1][];
        boolean submitted = false;
        try {
            settings();
            Objects.requireNonNull(capability);
            if (name == null || name.isBlank() || name.length() > 120 || name.chars().anyMatch(Character::isISOControl)
                    || outputLimit < 1)
                throw new IllegalArgumentException("Invalid model settings");
            var prior = new java.util.concurrent.atomic.AtomicReference<Optional<StoredAiProviderConfiguration>>(
                    Optional.empty());
            var priorLimit = new java.util.concurrent.atomic.AtomicReference<OptionalInt>(OptionalInt.empty());
            try (var operation = security.enterOperation(); var store = security.openCredentials()) {
                store.transaction(() -> {
                    prior.set(current(profile.id()));
                    priorLimit.set(persistence.aiProfiles().outputLimit(profile.id()));
                    if (prior.get().isPresent()
                            && !prior.get().orElseThrow().profile().credentialKey().equals(profile.credentialKey()))
                        throw new IllegalArgumentException("The existing credential reference cannot be replaced");
                    captured[0] = suppliedKey.length == 0
                            ? store.read(prior.get()
                                    .orElseThrow(() -> new IllegalArgumentException("An API key is required")).profile()
                                    .credentialKey())
                                    .orElseThrow(() -> new IllegalArgumentException("The saved API key is unavailable"))
                            : suppliedKey.clone();
                });
            }
            String credential = prior.get().map(value -> value.profile().credentialKey())
                    .orElseGet(() -> "ai/provider/" + profile.id() + "/api-key/" + UUID.randomUUID());
            var candidate = new AiProviderProfile(profile.id(), profile.chatCompletionsEndpoint(), profile.model(),
                    credential);
            boolean replaceKey = suppliedKey.length != 0;
            char[] key = captured[0];
            var handle = tasks.<Void>submit(new TaskRequest(PROBE_TYPE, "Model: " + name.strip(), List.of(), true),
                    task -> {
                        try {
                            var challenge = ModelCapabilityProbe.create(capability == AiCapabilityType.VISION,
                                    outputLimit);
                            var result = ai.probe(candidate.stored(), key, challenge.request(), task);
                            if (result.outcome() != Outcome.SUCCEEDED)
                                return new OperationResult<>(result.outcome(), Optional.empty(), result.problem());
                            if (!challenge.accepts(result.value().orElseThrow()))
                                return failed(ProblemCode.CONTRACT_VIOLATION, "MODEL_PROBE_ANSWER",
                                        "The model did not answer the generated capability challenge");
                            if (task.cancellation().requested())
                                return new OperationResult<>(Outcome.CANCELLED, Optional.empty(), Optional.empty());
                            try (var store = security.openCredentials()) {
                                store.transaction(() -> {
                                    var now = current(profile.id());
                                    if (!sameRevision(prior.get(), now) || !priorLimit.get()
                                            .equals(persistence.aiProfiles().outputLimit(profile.id())))
                                        throw new SQLException("Model changed during probe", "40001");
                                    if (replaceKey || now.isEmpty())
                                        store.save(candidate.credentialKey(), key);
                                    persistence.aiProfiles().saveVerified(candidate.stored(), name, Instant.now(),
                                            capability);
                                    persistence.aiProfiles().saveOutputLimit(profile.id(), outputLimit);
                                });
                            } catch (SQLException conflict) {
                                if ("40001".equals(conflict.getSQLState()))
                                    return failed(ProblemCode.BUSY, "MODEL_PROBE_STALE",
                                            "Model settings changed during verification; reload before editing");
                                runtime.reportUncertainEffect(task);
                                return failed(ProblemCode.UNKNOWN, "MODEL_SAVE_UNKNOWN",
                                        "The verified model commit must be reconciled before continuing");
                            } catch (gold.debug.windowstolinux.app.secret.SecretStoreException uncertain) {
                                runtime.reportUncertainEffect(task);
                                return failed(ProblemCode.UNKNOWN, "MODEL_SAVE_UNKNOWN",
                                        "The protected model commit could not be confirmed");
                            }
                            return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
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
            Arrays.fill(suppliedKey, '\0');
            if (!submitted && captured[0] != null)
                Arrays.fill(captured[0], '\0');
        }
    }

    /** Reads one authoritative revision. / 读取一个权威修订。
     * @param id exact model / 精确模型
     * @return current row or absent / 当前行或缺失
     * @throws SQLException if storage fails / 存储失败时 */
    private Optional<StoredAiProviderConfiguration> current(String id) throws SQLException {
        return persistence.aiProfiles().listConfigured().stream().filter(value -> value.profile().id().equals(id))
                .findFirst();
    }

    /** Rejects concurrent configuration changes before saving evidence. / 保存证据前拒绝并发配置变化。
     * @param expected observed revision / 已观察修订
     * @param actual current revision / 当前修订
     * @return whether connection identity still matches / 连接身份是否仍匹配 */
    private static boolean sameRevision(Optional<StoredAiProviderConfiguration> expected,
            Optional<StoredAiProviderConfiguration> actual) {
        return expected.equals(actual);
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
        return new OperationResult<>(Outcome.FAILED, Optional.empty(),
                Optional.of(new Problem(code, event, message, Map.of(), Optional.empty())));
    }
}
