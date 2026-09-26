package gold.debug.windowstolinux.app.service.ai;

import java.io.IOException;
import java.net.URI;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;

import gold.debug.windowstolinux.app.ai.protocol.ModelMessageCodec;
import gold.debug.windowstolinux.app.ai.transport.ModelHttpCall;
import gold.debug.windowstolinux.app.db.PlatformDatabase;
import gold.debug.windowstolinux.app.db.entity.StoredAiProviderConfiguration;
import gold.debug.windowstolinux.app.db.persistence.repository.AiProfileRepository;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.app.service.task.*;
import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.shared.model.ai.AiPurposeType;

/** Routes generic model calls through frozen purpose selections and original task credentials. / 经冻结用途选择及原任务凭据路由通用模型调用。 */
public final class PlatformAiService implements AiService, AutoCloseable {
    /** Actual task identity and resource authority. / 实际任务身份及资源授权方。 */
    private final PlatformTaskRuntime tasks;

    /** Original credential scopes. / 原凭据作用域。 */
    private final DesktopSecurityService security;

    /** Authoritative model configuration. / 权威模型配置。 */
    private final AiProfileRepository profiles;

    /** Exact activating Mod. / 精确正在启用的 Mod。 */
    private final String owner;

    /** Actual activating release. / 实际正在启用的版本。 */
    private final String version;

    /** Synchronous configuration and callback ownership. / 同步配置及回调归属。 */
    private final TaskCapabilityScope scope;

    /** Actual HTTP clients whose release has not been recorded. / 尚未登记释放的实际 HTTP 客户端。 */
    private final Set<PendingCall> retained = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Binds one activation to the platform model inventory. / 将一次启用绑定到平台模型库存。
     * @param database current engine / 当前引擎
     * @param tasks central task authority / 中央任务授权方
     * @param security current protected credentials / 当前受保护凭据
     * @param profiles actual model configuration / 实际模型配置
     * @param owner exact Mod owner / 精确 Mod 所有者
     * @param version loaded release / 已加载版本
     */
    public PlatformAiService(PlatformDatabase database, PlatformTaskRuntime tasks, DesktopSecurityService security,
            AiProfileRepository profiles, String owner, String version) {
        this.tasks = Objects.requireNonNull(tasks);
        this.security = Objects.requireNonNull(security);
        this.profiles = Objects.requireNonNull(profiles);
        this.owner = Objects.requireNonNull(owner);
        this.version = Objects.requireNonNull(version);
        scope = new TaskCapabilityScope(database, tasks, owner, version, Optional.empty(), "ai-access-");
    }

    /**
     * Calls verified purpose members within one deadline, never falling through uncertainty. / 在同一期限内调用已验证用途成员，遇未知结果不继续尝试。
     * @param request bounded immutable model request / 有界不可变模型请求
     * @param task authentic executing task / 真实执行任务
     * @return actual response or classified operation result / 实际响应或已分类操作结果
     * @throws ApiException if authorization, configuration or actual cleanup fails / 授权、配置或实际清理失败时
     */
    @Override
    public OperationResult<AiResponse> call(AiRequest request, TaskContext task) throws ApiException {
        return call(request, task, false);
    }

    /** Applies a host-owned output ceiling within each selected model's configured limit. / 在每个所选模型的配置上限内应用宿主输出预算。
     * @param request host request with a maximum permitted output / 带最大允许输出的宿主请求
     * @param task original task / 原任务
     * @return actual routed result / 实际路由结果
     * @throws ApiException if configuration, authority or cleanup is unavailable / 配置、授权或清理不可用时 */
    public OperationResult<AiResponse> callWithinModelLimit(AiRequest request, TaskContext task) throws ApiException {
        return call(request, task, true);
    }

    /** Routes one original request while keeping explicit author budgets strict. / 路由一次原请求，同时保持作者显式预算的严格校验。
     * @param request bounded original request / 有界原请求
     * @param task original task / 原任务
     * @param hostCeiling whether the host accepts the smaller configured output / 宿主是否接受较小配置输出
     * @return actual routed result / 实际路由结果
     * @throws ApiException if configuration, authority or cleanup is unavailable / 配置、授权或清理不可用时 */
    private OperationResult<AiResponse> call(AiRequest request, TaskContext task, boolean hostCeiling)
            throws ApiException {
        if (request == null)
            throw error(ProblemCode.INVALID_INPUT, "AI_REQUEST", "A model request is required");
        long timeout;
        try {
            timeout = request.timeout().toNanos();
        } catch (ArithmeticException invalid) {
            throw error(ProblemCode.INVALID_INPUT, "AI_TIMEOUT", "Model timeout is too large");
        }
        long start = System.nanoTime();
        try {
            return scope.call(task, false, () -> {
                AiPurposeType purpose = switch (request.purpose()) {
                    case LANGUAGE -> AiPurposeType.DEPLOYMENT;
                    case APPROVAL -> AiPurposeType.APPROVAL;
                    case VISION -> AiPurposeType.VISION;
                };
                var candidates = profiles.configuredFor(purpose);
                if (candidates.isEmpty())
                    throw error(ProblemCode.UNAVAILABLE, "AI_PURPOSE_UNAVAILABLE",
                            "No enabled verified model is configured for this purpose");
                OperationResult<AiResponse> result = null;
                for (var candidate : candidates) {
                    try (var prepared = capture(candidate, purpose, task)) {
                        var effective = hostCeiling
                                ? new AiRequest(request.purpose(), request.messages(),
                                        Math.min(request.maxOutputTokens(), prepared.maxOutputTokens()),
                                        request.timeout(), request.jsonObjectResponse())
                                : request;
                        result = send(candidate.profile(), prepared, effective, task, start, timeout);
                    }
                    if (result.outcome() != Outcome.FAILED || result.problem().isEmpty()
                            || !result.problem().orElseThrow().eventCode().equals("AI_HTTP_REJECTED"))
                        return result;
                }
                return Objects.requireNonNull(result);
            });
        } catch (SQLException failure) {
            throw error(ProblemCode.IO_FAILURE, "AI_CONFIGURATION_STORAGE", "Model configuration could not be read");
        }
    }

    /** Sends a single host-owned candidate using the same transport lifetime as author calls. / 使用与作者调用相同的传输寿命发送单个宿主候选。
     * @param profile exact provider configuration / 精确提供者配置
     * @param key caller-owned temporary credential / 调用方持有的临时凭据
     * @param request platform-created probe request / 平台创建的探测请求
     * @param task original host task / 原宿主任务
     * @return actual probe outcome / 实际探测结果
     * @throws ApiException if authorization or cleanup fails / 授权或清理失败时 */
    OperationResult<AiResponse> probe(gold.debug.windowstolinux.app.db.entity.StoredAiProviderProfile profile,
            char[] key, AiRequest request, TaskContext task) throws ApiException {
        long start = System.nanoTime();
        try (var prepared = new Prepared(key.clone(), request.maxOutputTokens())) {
            return scope.call(task, false,
                    () -> send(profile, prepared, request, task, start, request.timeout().toNanos()));
        } catch (SQLException failure) {
            throw error(ProblemCode.IO_FAILURE, "AI_PROBE_STORAGE", "The probe resource could not be recorded");
        }
    }

    /** Owns encoding, one send and actual cleanup within the original deadline. / 在原始期限内持有编码、单次发送及实际清理。
     * @param profile exact captured provider / 精确已捕获提供者
     * @param prepared original credential and budget / 原凭据及预算
     * @param request bounded request / 有界请求
     * @param task original task / 原任务
     * @param start original start time / 原开始时间
     * @param timeout whole operation budget / 整体操作预算
     * @return classified provider result / 已分类提供者结果
     * @throws ApiException if input, authorization or cleanup fails / 输入、授权或清理失败时 */
    private OperationResult<AiResponse> send(gold.debug.windowstolinux.app.db.entity.StoredAiProviderProfile profile,
            Prepared prepared, AiRequest request, TaskContext task, long start, long timeout) throws ApiException {
        byte[] body;
        try {
            body = ModelMessageCodec.encode(profile.model(), request, prepared.maxOutputTokens());
        } catch (IllegalArgumentException invalid) {
            throw error(ProblemCode.INVALID_INPUT, "AI_REQUEST_BUDGET",
                    "Model input, capability or output budget is invalid");
        }
        boolean transferred = false;
        try {
            ResourceLease lease = tasks.retain(task,
                    new TaskResource("CONNECTION", "ai-http-" + UUID.randomUUID(), owner, false));
            var pending = new PendingCall(task, lease);
            synchronized (this) {
                retained.add(pending);
            }
            try {
                long remaining = timeout - (System.nanoTime() - start);
                if (remaining <= 0)
                    return new OperationResult<>(Outcome.FAILED, Optional.empty(),
                            Optional.of(new Problem(ProblemCode.TIMEOUT, "AI_BEFORE_SEND_TIMEOUT",
                                    "The request budget ended before another model request was sent", Map.of(),
                                    Optional.empty())));
                try {
                    pending.call = new ModelHttpCall(URI.create(profile.endpoint()), prepared.key(), body,
                            Duration.ofNanos(remaining), profile.id(), request.jsonObjectResponse());
                    transferred = true;
                } catch (IllegalArgumentException invalid) {
                    throw error(ProblemCode.UNAVAILABLE, "AI_CONFIGURATION_INVALID",
                            "The configured endpoint or credential is invalid");
                }
                var result = pending.call.execute(tasks.operationCancellation(task));
                if (result.outcome() == Outcome.UNKNOWN)
                    tasks.reportUncertainEffect(task);
                return result;
            } finally {
                synchronized (pending) {
                    pending.running = false;
                }
                try {
                    pending.close();
                } catch (ApiException incomplete) {
                    tasks.reportUncertainEffect(task);
                    throw incomplete;
                }
            }
        } finally {
            if (!transferred)
                Arrays.fill(body, (byte) 0);
        }
    }

    /**
     * Locks the selected revision while reading its limit and exact credential in one transaction. / 在同一事务读取上限和精确凭据期间锁定所选修订。
     * @param expected frozen purpose member / 冻结用途成员
     * @param purpose requested model purpose / 请求的模型用途
     * @param task authentic original task / 真实原任务
     * @return copied credential and configured limit / 复制的凭据和已配置上限
     * @throws ApiException if configuration or credential authorization fails / 配置或凭据授权失败时
     */
    private Prepared capture(StoredAiProviderConfiguration expected, AiPurposeType purpose, TaskContext task)
            throws ApiException {
        char[][] key = new char[1][];
        int[] limit = new int[1];
        try {
            tasks.authorized(task, owner, version, () -> {
                try (var store = security.openCredentials()) {
                    store.transaction(() -> {
                        profiles.requireInvocation(expected, purpose);
                        limit[0] = profiles.outputLimit(expected.profile().id()).orElseThrow(
                                () -> new SQLException("Model output limit has not been configured", "55000"));
                        key[0] = store.read(expected.profile().credentialKey())
                                .orElseThrow(() -> new SQLException("Model credential is unavailable", "02000"));
                    });
                }
                return null;
            });
            var prepared = new Prepared(key[0], limit[0]);
            key[0] = null;
            return prepared;
        } catch (gold.debug.windowstolinux.app.secret.SecretStoreException failure) {
            throw error(ProblemCode.UNAVAILABLE, "AI_CONFIGURATION_UNAVAILABLE",
                    "The selected model, output limit or protected credential is unavailable");
        } catch (ApiException failure) {
            throw failure;
        } catch (Exception failure) {
            throw error(ProblemCode.UNAVAILABLE, "AI_CONFIGURATION_UNAVAILABLE",
                    "The selected model, output limit or protected credential is unavailable");
        } finally {
            if (key[0] != null)
                Arrays.fill(key[0], '\0');
        }
    }

    /**
     * Retries physical and durable cleanup only for the original retained task. / 仅为原保留任务重试物理及持久清理。
     * @param task authentic original recovery context / 真实原恢复上下文
     * @throws ApiException if any actual resource remains / 任一实际资源仍存在时
     */
    public void closeTaskResources(TaskContext task) throws ApiException {
        scope.retryFinishedReleases(task);
        List<PendingCall> calls;
        synchronized (this) {
            calls = List.copyOf(retained);
        }
        for (var pending : calls)
            if (pending.task == task)
                pending.close();
    }

    /**
     * Seals new work and closes only returned requests. / 封闭新操作并仅关闭已返回请求。
     * @throws ApiException if work or physical release remains / 仍有操作或物理释放时
     */
    @Override
    public void close() throws ApiException {
        scope.close();
        List<PendingCall> calls;
        synchronized (this) {
            calls = List.copyOf(retained);
        }
        for (var pending : calls)
            pending.close();
    }

    /** Builds a failure without provider bodies, prompts or credentials. / 构造不含提供者正文、提示词或凭据的失败。
     * @param code public category / 公开类别
     * @param event stable diagnostic identity / 稳定诊断身份
     * @param message safe explanation / 安全说明
     * @return public failure / 公开失败 */
    private static ApiException error(ProblemCode code, String event, String message) {
        return new ApiException(new Problem(code, event, message, Map.of(), Optional.empty()));
    }

    /** Scoped credential copy and explicit limit. / 限定凭据副本和明确上限。
     * @param key owned credential characters / 所属凭据字符
     * @param maxOutputTokens configured ceiling / 已配置上限 */
    private record Prepared(char[] key, int maxOutputTokens) implements AutoCloseable {
        /** Clears the credential after this candidate's request. / 当前候选请求后清除凭据。 */
        @Override
        public void close() {
            Arrays.fill(key, '\0');
        }
    }

    /** Owns a real client until physical and ledger cleanup both complete. / 持有真实客户端，直至物理及账本清理均完成。 */
    private final class PendingCall {
        /** Authentic original task. / 真实原任务。 */
        private final TaskContext task;

        /** Explicit actual-connection occupancy. / 明确的实际连接占用。 */
        private final ResourceLease lease;

        /** Actual client once constructed. / 构造后的实际客户端。 */
        private ModelHttpCall call;

        /** Whether synchronous sending still owns the client. / 同步发送是否仍持有客户端。 */
        private boolean running = true;

        /** Whether durable release completed. / 持久释放是否完成。 */
        private boolean released;

        /** Captures original resource ownership before client creation. / 创建客户端前取得原资源归属。
         * @param task original task / 原任务
         * @param lease retained connection marker / 保留连接标记 */
        private PendingCall(TaskContext task, ResourceLease lease) {
            this.task = task;
            this.lease = lease;
        }

        /** Retries real shutdown before releasing the original lease. / 释放原占用前重试实际关闭。
         * @throws ApiException if physical work or durable release remains / 仍有物理操作或持久释放时 */
        private synchronized void close() throws ApiException {
            if (released)
                return;
            if (running)
                throw error(ProblemCode.BUSY, "AI_CALL_ACTIVE", "The model call is still returning");
            try {
                if (call != null)
                    call.close();
            } catch (IOException incomplete) {
                throw error(ProblemCode.UNKNOWN, "AI_CALL_CLOSE", "The model connection has not finished closing");
            }
            lease.close();
            released = true;
            synchronized (PlatformAiService.this) {
                retained.remove(this);
            }
        }
    }
}
