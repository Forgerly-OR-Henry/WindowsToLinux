package gold.debug.windowstolinux.app.service.ai;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import gold.debug.windowstolinux.app.ai.model.AiCapabilityType;
import gold.debug.windowstolinux.app.ai.model.AiPurposeType;
import gold.debug.windowstolinux.app.ai.model.ModelHttpCall;
import gold.debug.windowstolinux.app.ai.model.ModelMessageCodec;
import gold.debug.windowstolinux.app.ai.voice.*;
import gold.debug.windowstolinux.app.ai.voice.VoiceHttpCall;
import gold.debug.windowstolinux.app.ai.voice.VoiceSocketSession;
import gold.debug.windowstolinux.app.db.ai.AiModelStore;
import gold.debug.windowstolinux.app.db.ai.StoredAiModelInfo;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.app.service.task.*;
import gold.debug.windowstolinux.app.service.task.PlatformTaskRuntime;
import gold.debug.windowstolinux.app.service.task.TaskCapabilityScope;
import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.ai.model.AiRequest;
import gold.debug.windowstolinux.appapi.ai.model.AiResponse;
import gold.debug.windowstolinux.appapi.ai.voice.VoiceEvent;
import gold.debug.windowstolinux.appapi.ai.voice.VoiceListener;
import gold.debug.windowstolinux.appapi.ai.voice.VoiceOptions;
import gold.debug.windowstolinux.appapi.ai.voice.VoiceRequest;
import gold.debug.windowstolinux.appapi.ai.voice.VoiceResponse;
import gold.debug.windowstolinux.appapi.ai.voice.VoiceSession;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.ResourceLease;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Routes generic model calls through frozen purpose selections and original task credentials. / 经冻结用途选择及原任务凭据路由通用模型调用。 */
public final class PlatformAiService implements AiService, AutoCloseable {
    /** Configured model connection timeout in seconds. / 已配置的模型连接超时秒数。 */
    private final int connectSeconds;

    /** Actual task identity and resource authority. / 实际任务身份及资源授权方。 */
    private final PlatformTaskRuntime tasks;

    /** Original credential scopes. / 原凭据作用域。 */
    private final DesktopSecurityService security;

    /** Authoritative model configuration. / 权威模型配置。 */
    private final AiModelStore profiles;

    /** Exact activating Mod. / 精确正在启用的 Mod。 */
    private final String owner;

    /** Actual activating release. / 实际正在启用的版本。 */
    private final String version;

    /** Synchronous configuration and callback ownership. / 同步配置及回调归属。 */
    private final TaskCapabilityScope scope;

    /** Actual HTTP clients whose release has not been recorded. / 尚未登记释放的实际 HTTP 客户端。 */
    private final Set<PendingCall> retained = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Real audio sessions retained through physical and durable release. / 持有至物理及持久释放的真实音频会话。 */
    private final Set<ManagedVoiceSession> voices = Collections.newSetFromMap(new IdentityHashMap<>());

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
                             AiModelStore profiles, String owner, String version) {
        this(database, tasks, security, profiles, owner, version, 10);
    }

    /** Captures the startup configuration. / 捕获启动配置。
     * @param database supplied startup dependency / 启动依赖
     * @param tasks supplied startup dependency / 启动依赖
     * @param security supplied startup dependency / 启动依赖
     * @param profiles supplied startup dependency / 启动依赖
     * @param owner supplied startup dependency / 启动依赖
     * @param version supplied startup dependency / 启动依赖
     * @param connectSeconds supplied startup dependency / 启动依赖
     */
    public PlatformAiService(PlatformDatabase database, PlatformTaskRuntime tasks, DesktopSecurityService security,
                             AiModelStore profiles, String owner, String version, int connectSeconds) {
        this.connectSeconds = connectSeconds;
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
        return call(request, task, false, Optional.empty());
    }

    /** Routes complete audio only through explicitly selected, file-capable voice models. / 完整音频仅经明确选入且具备文件能力的语音模型路由。
     * @param request immutable complete audio / 不可变完整音频
     * @param task authentic original task / 真实原任务
     * @return actual classified transcript result / 实际分类转写结果
     * @throws ApiException if route, authority or cleanup fails / 路由、授权或清理失败时 */
    @Override
    public OperationResult<VoiceResponse> call(VoiceRequest request, TaskContext task) throws ApiException {
        return callVoice(request, Optional.empty(), task);
    }

    /** Checks the same frozen routing revision before sending audio. / 发送音频前检查相同冻结路由修订。
     * @param request immutable complete audio / 不可变完整音频
     * @param expectedRevision original frozen routing digest / 原冻结路由摘要
     * @param task authentic original task / 真实原任务
     * @return actual classified transcript result / 实际分类转写结果
     * @throws ApiException if revision, route, authority or cleanup fails / 修订、路由、授权或清理失败时 */
    @Override
    public OperationResult<VoiceResponse> call(VoiceRequest request, String expectedRevision, TaskContext task)
        throws ApiException {
        requireRevision(expectedRevision);
        return callVoice(request, Optional.of(expectedRevision), task);
    }

    /** Holds routing and credential authority through one actual file request. / 在一次实际文件请求期间持有路由及凭据授权。
     * @param request bounded complete WAV / 有界完整 WAV
     * @param expectedRevision optional frozen route / 可选冻结路由
     * @param task original executing task / 原执行任务
     * @return actual file transcript outcome / 实际文件转写结果
     * @throws ApiException if authority, routing or physical cleanup fails / 授权、路由或物理清理失败时 */
    private OperationResult<VoiceResponse> callVoice(VoiceRequest request, Optional<String> expectedRevision,
                                                     TaskContext task) throws ApiException {
        Objects.requireNonNull(request);
        long start = System.nanoTime();
        try {
            return scope.call(task, false, () -> {
                var candidate = voiceCandidate(AiCapabilityType.VOICE_FILE, expectedRevision);
                try (var prepared = capture(candidate, AiPurposeType.VOICE, task, expectedRevision)) {
                    return sendVoice(candidate.profile(), prepared.key(), request, task, start);
                }
            });
        } catch (SQLException failure) {
            throw error(ProblemCode.IO_FAILURE, "AI_CONFIGURATION_STORAGE", "Model configuration could not be read");
        }
    }

    /** Opens one frozen actual audio-input session within the original task. / 在原任务内打开一个冻结的实际音频输入会话。
     * @param options finite session limits / 有限会话限制
     * @param listener original-task observations / 原任务观测
     * @param task authentic original task / 真实原任务
     * @return ready session with retained actual resources / 持有实际资源的就绪会话
     * @throws ApiException if route, authority or opening fails / 路由、授权或打开失败时 */
    @Override
    public VoiceSession openVoice(VoiceOptions options, VoiceListener listener, TaskContext task)
        throws ApiException {
        Objects.requireNonNull(options);
        Objects.requireNonNull(listener);
        long start = System.nanoTime();
        try {
            return scope.call(task, false, () -> {
                var candidate = voiceCandidate(AiCapabilityType.VOICE_REALTIME, Optional.empty());
                try (var prepared = capture(candidate, AiPurposeType.VOICE, task, Optional.empty())) {
                    return openVoice(candidate.profile(), prepared.key(), VoiceBudget.remaining(options, start), listener, task);
                }
            });
        } catch (SQLException failure) {
            throw error(ProblemCode.IO_FAILURE, "AI_CONFIGURATION_STORAGE", "Model configuration could not be read");
        }
    }

    /** Selects the first explicitly routed model with the requested verified input mode. / 选择明确用途路由中首个已验证所需输入方式的模型。
     * @param capability exact voice input mode / 精确语音输入方式
     * @param revision optional frozen whole-route digest / 可选冻结整路由摘要
     * @return selected frozen configuration / 所选冻结配置
     * @throws SQLException if route storage cannot be read / 路由存储无法读取时
     * @throws ApiException if mode, route or revision is unavailable / 方式、路由或修订不可用时 */
    private StoredAiModelInfo voiceCandidate(AiCapabilityType capability, Optional<String> revision)
        throws SQLException, ApiException {
        var candidates = revision.isPresent() ? routingSnapshotFor(revision.orElseThrow(), AiPurposeType.VOICE)
            : profiles.configuredFor(AiPurposeType.VOICE);
        if (candidates.isEmpty())
            throw error(ProblemCode.UNAVAILABLE, "AI_PURPOSE_UNAVAILABLE", "No enabled verified voice model is configured");
        return candidates.stream().filter(value -> value.verified(capability)).findFirst().orElseThrow(() ->
            error(ProblemCode.UNAVAILABLE, "VOICE_MODE_UNAVAILABLE", "No selected model supports the requested voice input mode"));
    }

    /** Requires a complete non-secret routing digest. / 要求完整且不含秘密的路由摘要。
     * @param revision requested frozen digest / 请求冻结摘要
     * @throws ApiException if the digest format is invalid / 摘要格式无效时 */
    private static void requireRevision(String revision) throws ApiException {
        if (revision == null || !revision.matches("[0-9a-f]{64}"))
            throw error(ProblemCode.INVALID_INPUT, "AI_REVISION", "A frozen AI configuration revision is required");
    }

    /** Probes file audio without enrolling a model into a business purpose. / 探测文件音频，不将模型纳入业务用途。
     * @param profile saved unverified configuration / 已保存未验证配置
     * @param key temporary protected credential / 临时受保护凭据
     * @param request bounded WAV probe / 有界 WAV 探测
     * @param task original verification task / 原验证任务
     * @return actual classified probe result / 实际分类探测结果
     * @throws ApiException if authority, resource storage or cleanup fails / 授权、资源存储或清理失败时 */
    OperationResult<VoiceResponse> probeVoice(gold.debug.windowstolinux.app.db.ai.StoredAiModelProfile profile,
                                              char[] key, VoiceRequest request, TaskContext task) throws ApiException {
        long start = System.nanoTime();
        try (var prepared = new Prepared(key.clone(), 1)) {
            return scope.call(task, false, () -> sendVoice(profile, prepared.key(), request, task, start));
        } catch (SQLException failure) {
            throw error(ProblemCode.IO_FAILURE, "AI_PROBE_STORAGE", "The voice probe resource could not be recorded");
        }
    }

    /** Probes real PCM input and waits for a confirmed transcript. / 探测真实 PCM 输入并等待已确认转写。
     * @param profile saved unverified configuration / 已保存未验证配置
     * @param key temporary protected credential / 临时受保护凭据
     * @param options bounded session limits / 有界会话限制
     * @param pcm actual validated mono PCM16 input / 实际已校验单声道 PCM16 输入
     * @param task original verification task / 原验证任务
     * @return actual classified transcript result / 实际分类转写结果
     * @throws ApiException if authority, opening, observation or cleanup fails / 授权、打开、观测或清理失败时 */
    OperationResult<VoiceResponse> probeRealtime(gold.debug.windowstolinux.app.db.ai.StoredAiModelProfile profile,
                                                 char[] key, VoiceOptions options, byte[] pcm, TaskContext task) throws ApiException {
        long start = System.nanoTime();
        try (var prepared = new Prepared(key.clone(), 1)) {
            return scope.call(task, false, () -> {
                try (var session = openVoice(profile, prepared.key(), VoiceBudget.remaining(options, start), ignored -> {
                }, task)) {
                    for (int offset = 0; offset < pcm.length; offset += 65_536) {
                        byte[] chunk = Arrays.copyOfRange(pcm, offset, Math.min(pcm.length, offset + 65_536));
                        try {
                            session.send(chunk).toCompletableFuture().get();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            session.cancel();
                            return session.completion().toCompletableFuture().join();
                        } catch (ExecutionException failure) {
                            return session.completion().toCompletableFuture().join();
                        } finally {
                            Arrays.fill(chunk, (byte) 0);
                        }
                    }
                    try {
                        return session.finish().toCompletableFuture().get();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        session.cancel();
                        return session.completion().toCompletableFuture().join();
                    } catch (ExecutionException failure) {
                        throw error(ProblemCode.UNKNOWN, "VOICE_PROBE_UNKNOWN", "Realtime probe completion could not be established");
                    }
                }
            });
        } catch (SQLException failure) {
            throw error(ProblemCode.IO_FAILURE, "AI_PROBE_STORAGE", "The realtime probe resource could not be recorded");
        }
    }

    /** Retains an actual file client until physical termination and durable original-task release. / 保留实际文件客户端，直至物理终止及原任务持久释放。
     * @param profile exact provider configuration / 精确提供方配置
     * @param key temporary credential / 临时凭据
     * @param request complete validated WAV / 完整已校验 WAV
     * @param task original executing task / 原执行任务
     * @param start original monotonic deadline start / 原单调期限起点
     * @return classified actual file result / 分类后的实际文件结果
     * @throws ApiException if admission, configuration or cleanup fails / 准入、配置或清理失败时 */
    private OperationResult<VoiceResponse> sendVoice(gold.debug.windowstolinux.app.db.ai.StoredAiModelProfile profile,
                                                     char[] key, VoiceRequest request, TaskContext task, long start) throws ApiException {
        ResourceLease lease = tasks.retain(task, new TaskResource("CONNECTION", "ai-http-" + UUID.randomUUID(), owner, false));
        var pending = new PendingCall(task, lease);
        synchronized (this) {
            retained.add(pending);
        }
        try {
            try {
                pending.voiceCall = new VoiceHttpCall(URI.create(profile.fileEndpoint()), key, profile.model(), profile.id(), request,
                    VoiceBudget.remaining(request.timeout(), start), connectSeconds);
            } catch (IllegalArgumentException invalid) {
                throw error(ProblemCode.UNAVAILABLE, "VOICE_CONFIGURATION_INVALID", "The file transcription configuration is invalid");
            }
            var result = pending.voiceCall.execute(tasks.operationCancellation(task));
            if (result.outcome() == TaskResult.UNKNOWN) tasks.reportUncertainEffect(task);
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
    }

    /** Original voice deadline after routing and protected credential capture. / 路由和受保护凭据捕获后的原语音截止预算。 */
    private static final class VoiceBudget {
        /** Deducts routing and credential time from the original deadline. / 从原期限扣除路由及凭据时间。
         * @param timeout original positive deadline / 原正时限
         * @param start original monotonic start / 原单调起点
         * @return strictly positive remaining budget / 严格为正的剩余预算
         * @throws ApiException if no budget remains before sending / 发送前已无剩余预算时 */
        private static Duration remaining(Duration timeout, long start) throws ApiException {
            long nanos = timeout.toNanos() - (System.nanoTime() - start);
            if (nanos <= 0)
                throw error(ProblemCode.TIMEOUT, "VOICE_BEFORE_SEND_TIMEOUT", "Voice deadline ended before connection admission");
            return Duration.ofNanos(nanos);
        }

        /** Keeps frozen queue bounds while deducting elapsed admission time. / 扣除已用准入时间，同时保持冻结队列限制。
         * @param options original immutable limits / 原不可变限制
         * @param start original monotonic start / 原单调起点
         * @return limits with the remaining original deadline / 使用原剩余期限的限制
         * @throws ApiException if admission exhausted the deadline / 准入已耗尽期限时 */
        private static VoiceOptions remaining(VoiceOptions options, long start) throws ApiException {
            return new VoiceOptions(remaining(options.timeout(), start), options.maxBufferedBytes());
        }
    }

    /** Binds the actual frozen socket and callbacks to an original retained task resource. / 将实际冻结套接字及回调绑定至原保留任务资源。
     * @param profile exact provider configuration / 精确提供方配置
     * @param key temporary captured credential / 临时捕获凭据
     * @param options remaining original limits / 原剩余限制
     * @param listener original-task listener / 原任务监听器
     * @param task authentic executing task / 真实执行任务
     * @return task-owned ready session / 任务所属就绪会话
     * @throws ApiException if callback admission, connection or cleanup fails / 回调准入、连接或清理失败时 */
    private ManagedVoiceSession openVoice(gold.debug.windowstolinux.app.db.ai.StoredAiModelProfile profile,
                                          char[] key, VoiceOptions options, VoiceListener listener, TaskContext task) throws ApiException {
        var callback = tasks.callbacks().<VoiceEvent, Void, Void>hostCallback(task, owner, version, (event, ignored) -> {
            listener.onEvent(event);
            return null;
        });
        ResourceLease lease = tasks.retain(task, new TaskResource("CONNECTION", "ai-voice-" + UUID.randomUUID(), owner, false));
        var managed = new ManagedVoiceSession(task, lease);
        synchronized (this) {
            voices.add(managed);
        }
        try {
            managed.session = VoiceSocketSession.open(URI.create(profile.realtimeEndpoint()), key, profile.model(), profile.id(),
                options, event -> {
                    try {
                        callback.apply(event, null);
                    } catch (ApiException expired) {
                        throw new IllegalStateException("Original voice callback scope ended");
                    }
                }, tasks.operationCancellation(task), session -> managed.session = session, connectSeconds);
            managed.session.completion().whenComplete((result, failure) -> managed.complete(result, failure));
            return managed;
        } catch (ApiException | RuntimeException failure) {
            try {
                if (managed.session != null) managed.session.close();
                managed.release();
            } catch (ApiException cleanup) {
                tasks.reportUncertainEffect(task);
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** Captures an opaque revision of all ordered purpose routes and their exact credentials and output limits. / 捕获全部有序用途路由及其精确凭据引用、输出上限的不透明修订。
     * @param purpose requested available purpose / 所需可用用途
     * @param task original task / 原任务
     * @return frozen non-secret revision / 冻结的不含秘密修订
     * @throws ApiException if authorization or configuration fails / 授权或配置失败时 */
    @Override
    public String configurationRevision(AiPurpose purpose, TaskContext task) throws ApiException {
        Objects.requireNonNull(purpose);
        try {
            return scope.call(task, false, () -> {
                var snapshot = routingSnapshot();
                if (snapshot.routes().get(mapped(purpose)).isEmpty())
                    throw error(ProblemCode.UNAVAILABLE, "AI_PURPOSE_UNAVAILABLE",
                        "No enabled verified model is configured for this purpose");
                return snapshot.revision();
            });
        } catch (SQLException failure) {
            throw error(ProblemCode.IO_FAILURE, "AI_CONFIGURATION_STORAGE", "Model configuration could not be read");
        }
    }

    /** Uses only a previously frozen whole-routing revision. / 仅使用此前冻结的完整路由修订。
     * @param request bounded original request / 有界原请求
     * @param expectedRevision frozen non-secret revision / 冻结不含秘密修订
     * @param task original task / 原任务
     * @return actual classified result / 实际分类结果
     * @throws ApiException if the route changed or access fails / 路由改变或访问失败时 */
    @Override
    public OperationResult<AiResponse> call(AiRequest request, String expectedRevision, TaskContext task)
        throws ApiException {
        if (expectedRevision == null || !expectedRevision.matches("[0-9a-f]{64}"))
            throw error(ProblemCode.INVALID_INPUT, "AI_REVISION", "A frozen AI configuration revision is required");
        return call(request, task, false, Optional.of(expectedRevision));
    }

    /** Applies a host-owned output ceiling within each selected model's configured limit. / 在每个所选模型的配置上限内应用宿主输出预算。
     * @param request host request with a maximum permitted output / 带最大允许输出的宿主请求
     * @param task original task / 原任务
     * @return actual routed result / 实际路由结果
     * @throws ApiException if configuration, authority or cleanup is unavailable / 配置、授权或清理不可用时 */
    public OperationResult<AiResponse> callWithinModelLimit(AiRequest request, TaskContext task) throws ApiException {
        return call(request, task, true, Optional.empty());
    }

    /** Generates a bounded file explanation using only the first enabled language model. / 仅使用首个启用语言模型生成有界文件说明。
     * @param request platform-owned LANGUAGE request / 平台持有的 LANGUAGE 请求
     * @param task authentic original task / 真实原任务
     * @return first model feedback without rotation / 不轮换的首模型反馈
     * @throws ApiException if request, authority, route or cleanup fails / 请求、授权、路由或清理失败时 */
    public OperationResult<AiResponse> explainFile(AiRequest request, TaskContext task) throws ApiException {
        if (request == null || request.purpose() != AiPurpose.LANGUAGE)
            throw error(ProblemCode.INVALID_INPUT, "AI_FILE_EXPLANATION_PURPOSE", "File explanations require the language purpose");
        return call(request, task, true, Optional.empty(), true);
    }

    /** Routes one original request while keeping explicit author budgets strict. / 路由一次原请求，同时保持作者显式预算的严格校验。
     * @param request bounded original request / 有界原请求
     * @param task original task / 原任务
     * @param hostCeiling whether the host accepts the smaller configured output / 宿主是否接受较小配置输出
     * @param expectedRevision optional frozen whole-route identity / 可选冻结整路由身份
     * @return actual routed result / 实际路由结果
     * @throws ApiException if configuration, authority or cleanup is unavailable / 配置、授权或清理不可用时 */
    private OperationResult<AiResponse> call(AiRequest request, TaskContext task, boolean hostCeiling,
                                             Optional<String> expectedRevision) throws ApiException {
        return call(request, task, hostCeiling, expectedRevision, false);
    }

    /** Applies ordinary candidate routing or a host-owned first-model explanation policy. / 应用普通候选路由或宿主持有的首模型说明规则。
     * @param request original bounded request / 原有界请求
     * @param task authentic original task / 真实原任务
     * @param hostCeiling whether a smaller configured output is accepted / 是否接受较小配置输出
     * @param expectedRevision optional frozen routing digest / 可选冻结路由摘要
     * @param firstOnly whether only the first enabled language model may be used / 是否仅可使用首个启用语言模型
     * @return actual classified model result / 实际分类模型结果
     * @throws ApiException if request, authority, configuration or cleanup fails / 请求、授权、配置或清理失败时 */
    private OperationResult<AiResponse> call(AiRequest request, TaskContext task, boolean hostCeiling,
                                             Optional<String> expectedRevision, boolean firstOnly) throws ApiException {
        if (request == null || request.purpose() == AiPurpose.VOICE)
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
                AiPurposeType purpose = mapped(request.purpose());
                var candidates = firstOnly ? firstLanguage() : expectedRevision.isPresent()
                    ? routingSnapshotFor(expectedRevision.orElseThrow(), purpose)
                    : profiles.configuredFor(purpose);
                if (candidates.isEmpty())
                    throw error(ProblemCode.UNAVAILABLE, "AI_PURPOSE_UNAVAILABLE",
                        "No enabled verified model is configured for this purpose");
                OperationResult<AiResponse> result = null;
                for (var candidate : candidates) {
                    try (var prepared = capture(candidate, purpose, task, expectedRevision)) {
                        var effective = hostCeiling
                            ? new AiRequest(request.purpose(), request.messages(),
                            Math.min(request.maxOutputTokens(), prepared.maxOutputTokens()),
                            request.timeout(), request.jsonObjectResponse())
                            : request;
                        result = send(candidate.profile(), prepared, effective, task, start, timeout);
                    }
                    if (result.outcome() != TaskResult.FAILED || result.problem().isEmpty()
                        || !result.problem().orElseThrow().eventCode().equals("AI_HTTP_REJECTED"))
                        return result;
                }
                return Objects.requireNonNull(result);
            });
        } catch (SQLException failure) {
            throw error(ProblemCode.IO_FAILURE, "AI_CONFIGURATION_STORAGE", "Model configuration could not be read");
        }
    }

    /** Requires the first enabled language member itself to remain available, without skipping it. / 要求首个启用语言成员本身仍可用，不跳过该成员。
     * @return single selected language configuration / 唯一所选语言配置
     * @throws SQLException if routing storage cannot be read / 路由存储无法读取时
     * @throws ApiException if the first member is absent or unverified / 首个成员缺失或未验证时 */
    private List<StoredAiModelInfo> firstLanguage() throws SQLException, ApiException {
        var first = profiles.routingState().members().get(AiPurposeType.DEPLOYMENT).stream()
            .filter(gold.debug.windowstolinux.app.db.ai.AiModelStore.RoutingMember::enabled)
            .findFirst().map(gold.debug.windowstolinux.app.db.ai.AiModelStore.RoutingMember::configuration);
        if (first.isEmpty() || !first.orElseThrow().verified(AiCapabilityType.TEXT))
            throw error(ProblemCode.UNAVAILABLE, "AI_FIRST_LANGUAGE_UNAVAILABLE", "The first enabled language model is unavailable");
        return List.of(first.orElseThrow());
    }

    /** Sends a single host-owned candidate using the same transport lifetime as author calls. / 使用与作者调用相同的传输寿命发送单个宿主候选。
     * @param profile exact provider configuration / 精确提供者配置
     * @param key caller-owned temporary credential / 调用方持有的临时凭据
     * @param request platform-created probe request / 平台创建的探测请求
     * @param task original host task / 原宿主任务
     * @return actual probe outcome / 实际探测结果
     * @throws ApiException if authorization or cleanup fails / 授权或清理失败时 */
    OperationResult<AiResponse> probe(gold.debug.windowstolinux.app.db.ai.StoredAiModelProfile profile,
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
    private OperationResult<AiResponse> send(gold.debug.windowstolinux.app.db.ai.StoredAiModelProfile profile,
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
                    return new OperationResult<>(TaskResult.FAILED, Optional.empty(),
                        Optional.of(new Problem(ProblemCode.TIMEOUT, "AI_BEFORE_SEND_TIMEOUT",
                            "The request budget ended before another model request was sent", Map.of(),
                            Optional.empty())));
                try {
                    pending.call = new ModelHttpCall(URI.create(profile.endpoint()), prepared.key(), body,
                        Duration.ofNanos(remaining), profile.id(), request.jsonObjectResponse(), connectSeconds);
                    transferred = true;
                } catch (IllegalArgumentException invalid) {
                    throw error(ProblemCode.UNAVAILABLE, "AI_CONFIGURATION_INVALID",
                        "The configured endpoint or credential is invalid");
                }
                var result = pending.call.execute(tasks.operationCancellation(task));
                if (result.outcome() == TaskResult.UNKNOWN)
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

    /** Maps the public purpose to the platform's persisted route. / 将公开用途映射到平台持久路由。
     * @param purpose public purpose / 公开用途
     * @return persisted purpose / 持久用途 */
    private static AiPurposeType mapped(AiPurpose purpose) {
        return switch (purpose) {
            case LANGUAGE -> AiPurposeType.DEPLOYMENT;
            case APPROVAL -> AiPurposeType.APPROVAL;
            case VISION -> AiPurposeType.VISION;
            case VOICE -> AiPurposeType.VOICE;
        };
    }

    /** One immutable selected route used for both revision comparison and actual candidate iteration. / 同时用于修订比较与实际候选迭代的一份不可变已选路由。
     * @param revision opaque digest / 不透明摘要
     * @param routes exact ordered candidates / 精确有序候选
     * @param limits atomically captured model output limits / 原子捕获的模型输出限额 */
    private record RoutingSnapshot(String revision, Map<AiPurposeType, List<StoredAiModelInfo>> routes,
                                   Map<String, Integer> limits) {
    }

    /** Requires the frozen revision and returns the same captured candidate list for sending. / 要求冻结修订并返回同份捕获的候选列表以供发送。
     * @param expected frozen revision / 冻结修订
     * @param purpose requested purpose / 请求用途
     * @return original selected candidates / 原已选候选
     * @throws SQLException if routing cannot be read / 路由无法读取时
     * @throws ApiException if the revision changed / 修订改变时 */
    private List<StoredAiModelInfo> routingSnapshotFor(String expected, AiPurposeType purpose)
        throws SQLException, ApiException {
        var snapshot = routingSnapshot();
        if (!expected.equals(snapshot.revision()))
            throw error(ProblemCode.INVALID_INPUT, "AI_CONFIGURATION_CHANGED",
                "Frozen AI configuration changed before the model call");
        return snapshot.routes().get(purpose);
    }

    /** Captures all configured purpose members, activation state, credentials and output limits atomically. / 原子捕获全部已配置用途成员、启用状态、凭据与输出限额。
     * @return immutable route with opaque revision / 带不透明修订的不可变路由
     * @throws SQLException if model metadata or output limits are unavailable / 模型元数据或输出限额不可用时 */
    private RoutingSnapshot routingSnapshot() throws SQLException {
        var state = profiles.routingState();
        var routes = state.routes();
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            append(digest, "ai-routing-v2");
            for (AiPurposeType purpose : AiPurposeType.values()) {
                append(digest, purpose.name());
                var members = state.members().get(purpose);
                append(digest, Integer.toString(members.size()));
                for (var member : members) {
                    var candidate = member.configuration();
                    var profile = candidate.profile();
                    append(digest, Boolean.toString(member.enabled()));
                    append(digest, profile.id());
                    append(digest, profile.endpoint());
                    append(digest, profile.fileEndpoint());
                    append(digest, profile.realtimeEndpoint());
                    append(digest, profile.model());
                    append(digest, profile.credentialKey());
                    append(digest, Long.toString(candidate.revision()));
                    for (var capability : AiCapabilityType.values())
                        append(digest, Boolean.toString(candidate.verified(capability)));
                    append(digest, Integer.toString(state.outputLimits().get(profile.id())));
                }
            }
            return new RoutingSnapshot(HexFormat.of().formatHex(digest.digest()), routes, state.outputLimits());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK SHA-256 unavailable", impossible);
        }
    }

    /** Hashes one length-delimited non-secret routing field. / 对一项带长度的不含秘密路由字段求摘要。
     * @param digest current SHA-256 / 当前 SHA-256
     * @param value exact metadata / 精确元数据 */
    private static void append(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    /**
     * Locks the selected revision while reading its limit and exact credential in one transaction. / 在同一事务读取上限和精确凭据期间锁定所选修订。
     * @param expected frozen purpose member / 冻结用途成员
     * @param purpose requested model purpose / 请求的模型用途
     * @param task authentic original task / 真实原任务
     * @param expectedRevision optional whole-route revision to recheck before each candidate / 每个候选调用前重核的可选整路由修订
     * @return copied credential and configured limit / 复制的凭据和已配置上限
     * @throws ApiException if configuration or credential authorization fails / 配置或凭据授权失败时
     */
    private Prepared capture(StoredAiModelInfo expected, AiPurposeType purpose, TaskContext task,
                             Optional<String> expectedRevision) throws ApiException {
        char[][] key = new char[1][];
        int[] limit = new int[1];
        boolean[] changed = new boolean[1];
        try {
            tasks.callbacks().authorized(task, owner, version, () -> {
                try (var store = security.openCredentials()) {
                    store.transaction(() -> {
                        var snapshot = routingSnapshot();
                        if (expectedRevision.isPresent()
                            && !expectedRevision.orElseThrow().equals(snapshot.revision())) {
                            changed[0] = true;
                            throw new SQLException("Frozen AI configuration changed", "55000");
                        }
                        if (!snapshot.routes().get(purpose).contains(expected))
                            throw new SQLException("Captured model is no longer in the purpose route", "55000");
                        profiles.requireInvocation(expected, purpose);
                        limit[0] = snapshotLimit(snapshot, expected.profile().id());
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
            if (changed[0])
                throw error(ProblemCode.INVALID_INPUT, "AI_CONFIGURATION_CHANGED",
                    "Frozen AI configuration changed before the model call");
            throw error(ProblemCode.UNAVAILABLE, "AI_CONFIGURATION_UNAVAILABLE",
                "The selected model, output limit or protected credential is unavailable");
        } finally {
            if (key[0] != null)
                Arrays.fill(key[0], '\0');
        }
    }

    /** Requires the limit copied into the same routing transaction. / 要求同一路由事务中复制的上限。
     * @param snapshot consistent routing state / 一致路由状态
     * @param id exact model id / 精确模型标识
     * @return configured positive limit / 已配置的正上限
     * @throws SQLException if no limit exists / 上限不存在时 */
    private static int snapshotLimit(RoutingSnapshot snapshot, String id) throws SQLException {
        int limit = snapshot.limits().getOrDefault(id, -1);
        if (limit < 1)
            throw new SQLException("Model output limit has not been configured", "55000");
        return limit;
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
        List<ManagedVoiceSession> sessions;
        synchronized (this) {
            sessions = List.copyOf(voices);
        }
        for (var voice : sessions) if (voice.task == task) voice.drain();
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
        List<ManagedVoiceSession> sessions;
        synchronized (this) {
            sessions = List.copyOf(voices);
        }
        for (var voice : sessions) voice.drain();
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

        /** Actual file-audio client, when this is a transcription call. / 文件音频调用的实际客户端。 */
        private VoiceHttpCall voiceCall;

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
                if (voiceCall != null)
                    voiceCall.close();
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

    /** Task-bound session wrapper whose completion includes original lease release. / 完成状态包括原占用释放的任务绑定会话包装。 */
    private final class ManagedVoiceSession implements VoiceSession {
        /** Authentic original executing context. / 真实原执行上下文。 */
        private final TaskContext task;

        /** Original durable CONNECTION occupancy. / 原持久 CONNECTION 占用。 */
        private final ResourceLease lease;

        /** Completion after both actual shutdown and original durable release. / 实际关闭及原持久释放均完成后的完成状态。 */
        private final CompletableFuture<OperationResult<VoiceResponse>> complete = new CompletableFuture<>();

        /** Actual socket retained even if opening cleanup fails. / 即使打开清理失败仍保留的实际套接字。 */
        private VoiceSocketSession session;

        /** Positive durable lease-release evidence protected by this monitor. / 由此监视器保护的持久占用释放正面证据。 */
        private boolean released;

        /** Captures original physical-resource ownership before connecting. / 连接前捕获原物理资源归属。
         * @param task authentic original context / 真实原上下文
         * @param lease actual retained occupancy / 实际保留占用 */
        private ManagedVoiceSession(TaskContext task, ResourceLease lease) {
            this.task = task;
            this.lease = lease;
        }

        /** Records uncertainty and releases only physically terminated sessions. / 记录未知影响，仅释放已物理终止的会话。
         * @param result actual classified transport result / 实际分类传输结果
         * @param failure asynchronous transport failure / 异步传输失败 */
        private void complete(OperationResult<VoiceResponse> result, Throwable failure) {
            try {
                if (failure != null || result.outcome() == TaskResult.UNKNOWN) tasks.reportUncertainEffect(task);
                if (result != null && result.problem().map(Problem::eventCode).filter("VOICE_CLOSE_UNKNOWN"::equals).isPresent()) {
                    complete.complete(result);
                    return;
                }
                release();
                if (failure == null) complete.complete(result);
                else complete.completeExceptionally(failure);
            } catch (ApiException cleanup) {
                try {
                    tasks.reportUncertainEffect(task);
                } catch (
                    ApiException ignored) { /* Preserve the original cleanup failure and retained lease for reconciliation. / 保留原清理失败及占用，供核对处理。 */ }
                complete.completeExceptionally(cleanup);
            }
        }

        /** Removes occupancy only after its durable release succeeds. / 仅在持久释放成功后移除占用。
         * @throws ApiException if original resource release cannot be recorded / 原资源释放无法登记时 */
        private synchronized void release() throws ApiException {
            if (released) return;
            lease.close();
            released = true;
            synchronized (PlatformAiService.this) {
                voices.remove(this);
            }
        }

        /** Retries original physical cleanup before releasing occupancy. / 释放占用前重试原物理清理。
         * @throws ApiException if cleanup or durable release remains pending / 清理或持久释放仍待完成时 */
        private void drain() throws ApiException {
            if (session != null) {
                session.close();
                if (!session.resourcesClosed())
                    throw error(ProblemCode.BUSY, "VOICE_SESSION_CLOSING", "Voice resources are still closing");
            }
            release();
        }

        /** Checks original task authority before actual PCM admission. / 实际 PCM 准入前检查原任务授权。
         * @param audio copied mono PCM16 chunk / 复制的单声道 PCM16 音频块
         * @return actual serialized send completion / 实际串行发送完成
         * @throws ApiException if authority, input or transport admission fails / 授权、输入或传输准入失败时 */
        @Override
        public CompletionStage<Void> send(byte[] audio) throws ApiException {
            tasks.requireExecuting(task, owner, version);
            return session.send(audio);
        }

        /** Checks original task authority before committing one turn. / 提交一次轮次前检查原任务授权。
         * @return actual commit send completion / 实际提交发送完成
         * @throws ApiException if authority or current turn rejects commit / 授权或当前轮次拒绝提交时 */
        @Override
        public CompletionStage<Void> commit() throws ApiException {
            tasks.requireExecuting(task, owner, version);
            return session.commit();
        }

        /** Seals authentic input and observes both physical and durable release. / 封闭真实输入，并观察物理及持久释放。
         * @return classified completion after actual release / 实际释放后的分类完成
         * @throws ApiException if authority or current input is invalid / 授权或当前输入无效时 */
        @Override
        public CompletionStage<OperationResult<VoiceResponse>> finish() throws ApiException {
            tasks.requireExecuting(task, owner, version);
            session.finish();
            return completion();
        }

        /** Exposes terminal observation without allowing callers to forge completion. / 暴露终态观察，不允许调用方伪造完成。
         * @return terminal result including resource release / 包括资源释放的终态结果 */
        @Override
        public CompletionStage<OperationResult<VoiceResponse>> completion() {
            return complete.minimalCompletionStage();
        }

        /** Cancels original input; callbacks release occupancy only after they return. / 取消原输入；回调仅在返回后释放占用。
         * @throws ApiException if cleanup authority or physical cancellation fails / 清理授权或物理取消失败时 */
        @Override
        public void cancel() throws ApiException {
            tasks.requireCleanup(task, owner, version);
            session.cancel();
            if (session.resourcesClosed()) release();
        }

        /** Drains actual original resources without implicit input commit. / 排空实际原资源，不隐式提交输入。
         * @throws ApiException if cleanup authority, physical close or durable release fails / 清理授权、物理关闭或持久释放失败时 */
        @Override
        public void close() throws ApiException {
            tasks.requireCleanup(task, owner, version);
            drain();
        }
    }
}
