package gold.debug.windowstolinux.app.ai.model;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import gold.debug.windowstolinux.app.ai.http.HttpUrlChecker;
import gold.debug.windowstolinux.app.ai.http.LimitedHttpBodySubscriber;
import gold.debug.windowstolinux.appapi.ai.model.AiResponse;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;

/** Owns one non-replayed HTTP model request and its actual client shutdown. / 持有一个不重放的 HTTP 模型请求及其实际客户端关闭。 */
public final class ModelHttpCall implements AutoCloseable {

    /** Exact request with redirects disabled. / 禁止重定向的精确请求。 */
    private final HttpRequest request;

    /** Per-operation client whose physical lifetime can be checked. / 可核对物理寿命的单操作客户端。 */
    private final HttpClient client;

    /** Owned request bytes, cleared only after client termination. / 仅在客户端终止后清除的所属请求字节。 */
    private final byte[] body;

    /** Selected platform model identity. / 所选平台模型身份。 */
    private final String modelId;

    /** Requested response object format. / 请求的响应对象格式。 */
    private final boolean jsonObject;

    private final ModelProtocol protocol;

    private String continuation;

    public String continuation() {
        return continuation;
    }

    /** Overall transport time allowance. / 整体传输时限。 */
    private final long timeoutNanos;

    /** Start of client construction within the transport deadline. / 传输期限内开始构造客户端的时间。 */
    private final long createdAt = System.nanoTime();

    /** Whether the single send was already consumed. / 单次发送是否已消费。 */
    private boolean started;

    /** Whether teardown sealed new sends. / 拆除是否封闭新发送。 */
    private boolean closing;

    /** Current request future, when created. / 已创建时的当前请求 Future。 */
    private volatile CompletableFuture<HttpResponse<byte[]>> response;

    /**
     * Takes ownership of prepared request bytes without sending. / 接管准备好的请求字节，不发送。
     * @param endpoint exact configured complete endpoint / 精确配置的完整端点
     * @param apiKey temporary credential copied by the JDK header API / JDK 请求头 API 复制的临时凭据
     * @param body prepared bytes whose ownership transfers on successful construction / 构造成功后转移归属的准备字节
     * @param timeout positive transport deadline / 正的传输期限
     * @param modelId platform model identity / 平台模型身份
     * @param jsonObject whether object output is required / 是否要求对象输出
     */
    public ModelHttpCall(URI endpoint, char[] apiKey, byte[] body, Duration timeout, String modelId,
                         boolean jsonObject) {
        this(endpoint, apiKey, body, timeout, modelId, jsonObject,
            gold.debug.windowstolinux.app.config.AppConfig.defaultsConfig().ai().connectSeconds());
    }

    /** Captures the startup configuration. / 捕获启动配置。
     * @param endpoint supplied startup dependency / 启动依赖
     * @param apiKey supplied startup dependency / 启动依赖
     * @param body supplied startup dependency / 启动依赖
     * @param timeout supplied startup dependency / 启动依赖
     * @param modelId supplied startup dependency / 启动依赖
     * @param jsonObject supplied startup dependency / 启动依赖
     * @param connectSeconds supplied startup dependency / 启动依赖
     */
    public ModelHttpCall(URI endpoint, char[] apiKey, byte[] body, Duration timeout, String modelId,
                         boolean jsonObject, int connectSeconds) {
        this(endpoint, apiKey, body, timeout, modelId, jsonObject, connectSeconds, ModelProtocol.OPENAI_CHAT_COMPLETIONS);
    }

    public ModelHttpCall(URI endpoint, char[] apiKey, byte[] body, Duration timeout, String modelId,
                         boolean jsonObject, int connectSeconds, ModelProtocol protocol) {
        if (connectSeconds < 1) throw new IllegalArgumentException("Connect timeout must be positive");
        HttpUrlChecker.validate(endpoint);
        if (apiKey == null || apiKey.length == 0)
            throw new IllegalArgumentException("Model credential is absent");
        this.body = Objects.requireNonNull(body);
        this.modelId = Objects.requireNonNull(modelId);
        this.jsonObject = jsonObject;
        this.protocol = Objects.requireNonNull(protocol);
        timeoutNanos = timeout.toNanos();
        if (timeoutNanos <= 0)
            throw new IllegalArgumentException("Model timeout must be positive");
        var builder = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type", "application/json");
        if (protocol == ModelProtocol.ANTHROPIC_MESSAGES)
            builder.header("x-api-key", new String(apiKey)).header("anthropic-version", "2023-06-01");
        else builder.header("Authorization", "Bearer " + new String(apiKey));
        request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(timeout.compareTo(Duration.ofSeconds(connectSeconds)) < 0 ? timeout : Duration.ofSeconds(connectSeconds))
            .build();
    }

    /**
     * Sends at most once, treating post-send cancellation or transport loss as unknown. / 最多发送一次，将发送后的取消或传输丢失视为未知。
     * @param cancellation original task cancellation / 原任务取消
     * @return complete response, known HTTP failure or explicit uncertainty / 完整响应、已知 HTTP 失败或明确不确定性
     */
    public OperationResult<AiResponse> execute(CancelToken cancellation) {
        synchronized (this) {
            if (started || closing)
                throw new IllegalStateException("Model request is no longer available");
            started = true;
        }
        if (cancellation.requested() || Thread.currentThread().isInterrupted())
            return failure(TaskResult.CANCELLED, ProblemCode.CANCELLED, "AI_CANCELLED_BEFORE_SEND",
                "Model request was cancelled before sending", Map.of());
        if (System.nanoTime() - createdAt >= timeoutNanos)
            return failure(TaskResult.FAILED, ProblemCode.TIMEOUT, "AI_BEFORE_SEND_TIMEOUT",
                "The deadline expired before sending", Map.of());
        try {
            response = client.sendAsync(request,
                ignored -> new LimitedHttpBodySubscriber(ModelMessageCodec.MAX_RESPONSE_BYTES));
            for (; ; ) {
                if (cancellation.requested() || Thread.currentThread().isInterrupted()) {
                    response.cancel(true);
                    return failure(TaskResult.UNKNOWN, ProblemCode.CANCELLED, "AI_CANCELLED_AFTER_SEND",
                        "The provider may have processed the cancelled request", Map.of());
                }
                long remaining = timeoutNanos - (System.nanoTime() - createdAt);
                if (remaining <= 0) {
                    response.cancel(true);
                    return failure(TaskResult.UNKNOWN, ProblemCode.TIMEOUT, "AI_TIMEOUT",
                        "The provider outcome is unknown after the deadline", Map.of("timeoutPhase", "REQUEST_BUDGET"));
                }
                try {
                    var result = response.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)),
                        TimeUnit.NANOSECONDS);
                    byte[] bytes = result.body();
                    try {
                        if (result.statusCode() < 200 || result.statusCode() >= 300)
                            return failure(TaskResult.FAILED, ProblemCode.IO_FAILURE, "AI_HTTP_REJECTED",
                                "The selected provider rejected the request",
                                Map.of("httpStatus", Integer.toString(result.statusCode())));
                        try {
                            var decoded = ModelProtocolCodec.forProtocol(protocol).decode(bytes, modelId, jsonObject);
                            continuation = decoded.continuation();
                            return new OperationResult<>(TaskResult.SUCCEEDED,
                                Optional.of(decoded.response()),
                                Optional.empty());
                        } catch (IOException invalid) {
                            return failure(TaskResult.FAILED, ProblemCode.CONTRACT_VIOLATION, "AI_RESPONSE_INVALID",
                                "The provider response does not satisfy the requested format", ModelProtocolDiagnostics.shape(protocol, bytes));
                        }
                    } finally {
                        Arrays.fill(bytes, (byte) 0);
                    }
                } catch (TimeoutException waiting) {
                    /* Recheck the original deadline and cancellation. / 重新核对原期限和取消。 */
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (response != null)
                response.cancel(true);
            return failure(TaskResult.UNKNOWN, ProblemCode.CANCELLED, "AI_INTERRUPTED",
                "The interrupted provider outcome is unknown", Map.of());
        } catch (ExecutionException failure) {
            if (transportCause(failure) instanceof HttpTimeoutException)
                return failure(TaskResult.UNKNOWN, ProblemCode.TIMEOUT, "AI_TIMEOUT",
                    "The provider outcome is unknown after the deadline", transportDetails(failure));
            return failure(TaskResult.UNKNOWN, ProblemCode.IO_FAILURE, "AI_TRANSPORT_UNKNOWN",
                "The provider outcome could not be established", transportDetails(failure));
        } catch (RuntimeException failure) {
            return failure(TaskResult.UNKNOWN, ProblemCode.IO_FAILURE, "AI_TRANSPORT_UNKNOWN",
                "The provider outcome could not be established", transportDetails(failure));
        }
    }

    /** Keeps only bounded standard cause identities and fixed transport categories. / 仅保留有界标准异常链与固定传输类别。
     * @param failure transport failure without exposing its message / 不披露消息的传输异常
     * @return bounded diagnostic metadata / 有界诊断元数据 */
    static Map<String, String> transportDetails(Throwable failure) {
        Throwable cause = transportCause(failure);
        String type = cause.getClass().getName();
        var details = new HashMap<String, String>();
        details.put("transportFailureType", type.matches("(?:java|javax|jdk)[.][A-Za-z0-9_.$]{1,180}") ? type : "OTHER");
        if (cause instanceof HttpConnectTimeoutException) details.put("timeoutPhase", "HTTP_CONNECT");
        else if (cause instanceof HttpTimeoutException) details.put("timeoutPhase", "HTTP_REQUEST");
        var chain = new ArrayList<String>();
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable current = cause; current != null && chain.size() < 8 && visited.add(current); current = current.getCause()) {
            String name = current.getClass().getName();
            boolean standard = name.matches("(?:java|javax|jdk)[.][A-Za-z0-9_.$]{1,180}");
            chain.add(standard ? name : "OTHER");
            String message = standard ? current.getMessage() : null;
            if (message == null || details.containsKey("transportFailureKind")) continue;
            if (message.equals("HTTP/1.1 header parser received no bytes"))
                details.put("transportFailureKind", "HTTP_HEADERS_EMPTY");
            else if (message.matches("fixed content-length: [0-9]+, bytes received: [0-9]+"))
                details.put("transportFailureKind", "HTTP_BODY_INCOMPLETE");
            else if (message.matches("chunked transfer encoding, state: [A-Z_]+"))
                details.put("transportFailureKind", "HTTP_CHUNK_INCOMPLETE");
            else if (message.equals("AI response exceeded the configured transport limit"))
                details.put("transportFailureKind", "RESPONSE_BYTE_LIMIT");
        }
        if (chain.size() > 1) details.put("transportCauseTypes", String.join(">", chain));
        return Map.copyOf(details);
    }

    /** Unwraps only bounded asynchronous transport wrappers. / 仅有界展开异步传输包装。
     * @param failure original failure / 原异常
     * @return concrete transport cause or the remaining wrapper / 具体传输原因或剩余包装 */
    private static Throwable transportCause(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; depth < 8 && (cause instanceof ExecutionException || cause instanceof CompletionException)
            && cause.getCause() != null && cause.getCause() != cause; depth++)
            cause = cause.getCause();
        return cause;
    }

    /**
     * Stops the owned client and verifies physical termination before releasing request bytes. / 停止所属客户端并核对物理终止，随后释放请求字节。
     * @throws IOException if actual transport work still remains / 仍有实际传输操作时
     */
    @Override
    public void close() throws IOException {
        synchronized (this) {
            closing = true;
        }
        boolean interrupted = Thread.interrupted();
        try {
            var pending = response;
            if (pending != null && !pending.isDone())
                pending.cancel(true);
            client.shutdownNow();
            if (!client.awaitTermination(Duration.ofSeconds(2)))
                throw new IOException("Model HTTP client is still terminating");
            Arrays.fill(body, (byte) 0);
            continuation = null;
        } catch (InterruptedException waiting) {
            interrupted = true;
            throw new IOException("Model client termination was interrupted");
        } finally {
            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }

    /** Creates a diagnostic without provider payload or credential text. / 构造不含提供者载荷或凭据文本的诊断。
     * @param outcome operation outcome / 操作结果
     * @param code safe category / 安全类别
     * @param event stable event identity / 稳定事件身份
     * @param message safe explanation / 安全说明
     * @param context bounded non-secret details / 有界非秘密详情
     * @return classified public result / 已分类公开结果 */
    private OperationResult<AiResponse> failure(TaskResult outcome, ProblemCode code, String event, String message,
                                                Map<String, String> context) {
        var details = new HashMap<>(context);
        details.put("modelId", modelId);
        return new OperationResult<>(outcome, Optional.empty(),
            Optional.of(new Problem(code, event, message, details, Optional.empty())));
    }
}
