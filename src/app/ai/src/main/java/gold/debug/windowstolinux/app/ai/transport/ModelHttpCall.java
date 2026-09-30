package gold.debug.windowstolinux.app.ai.transport;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import gold.debug.windowstolinux.app.ai.execution.protocol.ModelMessageCodec;
import gold.debug.windowstolinux.appapi.ai.AiResponse;
import gold.debug.windowstolinux.appapi.task.*;

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
        validateEndpoint(endpoint);
        if (apiKey == null || apiKey.length == 0)
            throw new IllegalArgumentException("Model credential is absent");
        this.body = Objects.requireNonNull(body);
        this.modelId = Objects.requireNonNull(modelId);
        this.jsonObject = jsonObject;
        timeoutNanos = timeout.toNanos();
        if (timeoutNanos <= 0)
            throw new IllegalArgumentException("Model timeout must be positive");
        request = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + new String(apiKey))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout.compareTo(Duration.ofSeconds(10)) < 0 ? timeout : Duration.ofSeconds(10))
                .build();
    }

    /**
     * Validates the exact endpoint without guessing paths or forwarding credentials. / 校验精确端点，不猜路径或转发凭据。
     * @param endpoint configured complete URL / 配置的完整 URL
     * @throws IllegalArgumentException if the URL is not authorized by platform rules / URL 不满足平台规则时
     */
    public static void validateEndpoint(URI endpoint) {
        if (endpoint == null || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || !("https".equalsIgnoreCase(endpoint.getScheme()) || "http".equalsIgnoreCase(endpoint.getScheme())))
            throw new IllegalArgumentException(
                    "Model endpoint must be a complete HTTP(S) URL without credentials, query or fragment");
        if ("http".equalsIgnoreCase(endpoint.getScheme()) && !Set.of("localhost", "127.0.0.1", "::1", "[::1]")
                .contains(endpoint.getHost().toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("Remote model endpoints require HTTPS");
    }

    /**
     * Sends at most once, treating post-send cancellation or transport loss as unknown. / 最多发送一次，将发送后的取消或传输丢失视为未知。
     * @param cancellation original task cancellation / 原任务取消
     * @return complete response, known HTTP failure or explicit uncertainty / 完整响应、已知 HTTP 失败或明确不确定性
     */
    public OperationResult<AiResponse> execute(CancellationToken cancellation) {
        synchronized (this) {
            if (started || closing)
                throw new IllegalStateException("Model request is no longer available");
            started = true;
        }
        if (cancellation.requested() || Thread.currentThread().isInterrupted())
            return failure(Outcome.CANCELLED, ProblemCode.CANCELLED, "AI_CANCELLED_BEFORE_SEND",
                    "Model request was cancelled before sending", Map.of());
        if (System.nanoTime() - createdAt >= timeoutNanos)
            return failure(Outcome.FAILED, ProblemCode.TIMEOUT, "AI_BEFORE_SEND_TIMEOUT",
                    "The deadline expired before sending", Map.of());
        try {
            response = client.sendAsync(request,
                    ignored -> new LimitedHttpBodySubscriber(ModelMessageCodec.MAX_RESPONSE_BYTES));
            for (;;) {
                if (cancellation.requested() || Thread.currentThread().isInterrupted()) {
                    response.cancel(true);
                    return failure(Outcome.UNKNOWN, ProblemCode.CANCELLED, "AI_CANCELLED_AFTER_SEND",
                            "The provider may have processed the cancelled request", Map.of());
                }
                long remaining = timeoutNanos - (System.nanoTime() - createdAt);
                if (remaining <= 0) {
                    response.cancel(true);
                    return failure(Outcome.UNKNOWN, ProblemCode.TIMEOUT, "AI_TIMEOUT",
                            "The provider outcome is unknown after the deadline", Map.of());
                }
                try {
                    var result = response.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)),
                            TimeUnit.NANOSECONDS);
                    byte[] bytes = result.body();
                    try {
                        if (result.statusCode() < 200 || result.statusCode() >= 300)
                            return failure(Outcome.FAILED, ProblemCode.IO_FAILURE, "AI_HTTP_REJECTED",
                                    "The selected provider rejected the request",
                                    Map.of("httpStatus", Integer.toString(result.statusCode())));
                        try {
                            return new OperationResult<>(Outcome.SUCCEEDED,
                                    Optional.of(ModelMessageCodec.decode(bytes, modelId, jsonObject)),
                                    Optional.empty());
                        } catch (IOException invalid) {
                            return failure(Outcome.FAILED, ProblemCode.CONTRACT_VIOLATION, "AI_RESPONSE_INVALID",
                                    "The provider response does not satisfy the requested format", Map.of());
                        }
                    } finally {
                        Arrays.fill(bytes, (byte) 0);
                    }
                } catch (TimeoutException waiting) {
                    /* Recheck the original deadline and cancellation. / 重新核对原期限和取消。 */ }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (response != null)
                response.cancel(true);
            return failure(Outcome.UNKNOWN, ProblemCode.CANCELLED, "AI_INTERRUPTED",
                    "The interrupted provider outcome is unknown", Map.of());
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof HttpTimeoutException)
                return failure(Outcome.UNKNOWN, ProblemCode.TIMEOUT, "AI_TIMEOUT",
                        "The provider outcome is unknown after the deadline", Map.of());
            return failure(Outcome.UNKNOWN, ProblemCode.IO_FAILURE, "AI_TRANSPORT_UNKNOWN",
                    "The provider outcome could not be established", Map.of());
        } catch (RuntimeException failure) {
            return failure(Outcome.UNKNOWN, ProblemCode.IO_FAILURE, "AI_TRANSPORT_UNKNOWN",
                    "The provider outcome could not be established", Map.of());
        }
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
    private OperationResult<AiResponse> failure(Outcome outcome, ProblemCode code, String event, String message,
            Map<String, String> context) {
        var details = new HashMap<>(context);
        details.put("modelId", modelId);
        return new OperationResult<>(outcome, Optional.empty(),
                Optional.of(new Problem(code, event, message, details, Optional.empty())));
    }
}
