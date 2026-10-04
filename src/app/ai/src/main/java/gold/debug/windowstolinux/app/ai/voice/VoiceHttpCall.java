package gold.debug.windowstolinux.app.ai.voice;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import gold.debug.windowstolinux.app.ai.http.*;
import gold.debug.windowstolinux.appapi.ai.voice.VoiceRequest;
import gold.debug.windowstolinux.appapi.ai.voice.VoiceResponse;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;

/** One real multipart transcription request, never replayed after uncertainty. / 一个真实 multipart 转写请求，未知状态后不重放。 */
public final class VoiceHttpCall implements AutoCloseable {
    /** Dedicated transport retained through actual termination. / 持有至实际终止的专用传输。 */
    private final HttpClient client;

    /** Immutable destination, authorization and multipart publisher. / 不可变目的地、授权及 multipart 发布器。 */
    private final HttpRequest request;

    /** Owned multipart bytes cleared only after the publisher stops. / 发布器停止后才清除的所属 multipart 字节。 */
    private final byte[] body;

    /** Selected non-secret platform model identity. / 所选的不含秘密平台模型身份。 */
    private final String modelId;

    /** Original monotonic start before multipart and client construction. / multipart 及客户端构造前的原单调起点。 */
    private final long start = System.nanoTime();

    /** Remaining host time budget captured in nanoseconds. / 按纳秒捕获的剩余宿主时间预算。 */
    private final long budget;

    /** Single actual request future, also used for cancellation. / 唯一实际请求 future，同时用于取消。 */
    private volatile CompletableFuture<HttpResponse<byte[]>> response;

    /** One-send admission state protected by this call's monitor. / 由调用监视器保护的单次发送准入状态。 */
    private boolean started;

    /** Closed-admission state protected by this call's monitor. / 由调用监视器保护的关闭准入状态。 */
    private boolean closed;

    /** Captures validated audio and exact configured destination. / 捕获已校验音频和精确配置目的地。
     * @param endpoint exact configured file endpoint / 精确配置文件端点
     * @param key temporary credential / 临时凭据
     * @param model configured provider model / 已配置提供方模型
     * @param modelId platform model identity / 平台模型身份
     * @param input immutable WAV request / 不可变 WAV 请求 */
    public VoiceHttpCall(URI endpoint, char[] key, String model, String modelId, VoiceRequest input) {
        this(endpoint, key, model, modelId, input, input.timeout());
    }

    /** Uses the remaining host deadline after routing and credential capture. / 使用路由和凭据捕获后的剩余宿主时限。
     * @param endpoint exact configured file endpoint / 精确配置文件端点
     * @param key temporary credential / 临时凭据
     * @param model provider model / 提供方模型
     * @param modelId platform selection / 平台所选身份
     * @param input complete validated WAV / 完整已校验 WAV
     * @param timeout positive remaining deadline / 正剩余时限 */
    public VoiceHttpCall(URI endpoint, char[] key, String model, String modelId, VoiceRequest input, Duration timeout) {
        this(endpoint, key, model, modelId, input, timeout, gold.debug.windowstolinux.app.config.AppConfig.defaultsConfig().ai().connectSeconds());
    }

    /** Caps connection admission within the original operation deadline. / 在原操作时限内限制连接准入。
     * @param endpoint explicit destination / 明确目的地
     * @param key temporary credential / 临时凭据
     * @param model provider model / 提供方模型
     * @param modelId platform identity / 平台身份
     * @param input immutable audio / 不可变音频
     * @param timeout remaining operation budget / 剩余操作预算
     * @param connectSeconds startup HTTP cap / 启动 HTTP 上限 */
    public VoiceHttpCall(URI endpoint, char[] key, String model, String modelId, VoiceRequest input, Duration timeout, int connectSeconds) {
        if (connectSeconds < 1) throw new IllegalArgumentException("Connection cap must be positive");
        HttpUrlChecker.validate(endpoint);
        VoiceRequest.validateTimeout(timeout);
        VoiceProtocol.validateKey(key);
        if (model == null || !model.matches("[A-Za-z0-9._:/-]{1,128}"))
            throw new IllegalArgumentException("Invalid transcription configuration");
        this.modelId = Objects.requireNonNull(modelId);
        budget = timeout.toNanos();
        String boundary = "wtl-" + UUID.randomUUID();
        byte[] audio = input.audio();
        try (var bytes = new ByteArrayOutputStream(audio.length + 1024)) {
            field(bytes, boundary, "model", model);
            field(bytes, boundary, "response_format", "json");
            bytes.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + input.filename() + "\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            bytes.write(audio);
            bytes.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            body = bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("In-memory multipart encoding failed", impossible);
        } finally {
            Arrays.fill(audio, (byte) 0);
        }
        request = HttpRequest.newBuilder(endpoint).timeout(timeout)
            .header("Authorization", "Bearer " + new String(key))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(timeout.compareTo(Duration.ofSeconds(connectSeconds)) < 0 ? timeout : Duration.ofSeconds(connectSeconds))
            .version(HttpClient.Version.HTTP_1_1).build();
    }

    /** Writes one bounded multipart text field. / 写入一项有界 multipart 文本字段。
     * @param bytes owned multipart sink / 所属 multipart 接收端
     * @param boundary unique delimiter / 唯一分隔符
     * @param name fixed field identity / 固定字段标识
     * @param value validated field value / 已校验字段值
     * @throws IOException if the sink cannot accept bytes / 接收端无法接受字节时 */
    private static void field(OutputStream bytes, String boundary, String name, String value) throws IOException {
        bytes.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
            + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    /** Performs a single send within the original deadline. / 在原时限内只发送一次。
     * @param cancellation original task cancellation / 原任务取消
     * @return actual classified outcome / 实际分类结果 */
    public OperationResult<VoiceResponse> execute(CancelToken cancellation) {
        synchronized (this) {
            if (started || closed) throw new IllegalStateException("Voice call already consumed");
            started = true;
        }
        if (cancellation.requested() || Thread.currentThread().isInterrupted())
            return failure(TaskResult.CANCELLED, ProblemCode.CANCELLED, "VOICE_CANCELLED_BEFORE_SEND", "Voice input was cancelled before sending");
        if (System.nanoTime() - start >= budget)
            return failure(TaskResult.FAILED, ProblemCode.TIMEOUT, "VOICE_BEFORE_SEND_TIMEOUT", "Voice deadline ended before sending");
        try {
            response = client.sendAsync(request, ignored -> new LimitedHttpBodySubscriber(VoiceProtocol.MAX_RESPONSE_BYTES));
            for (; ; ) {
                if (cancellation.requested() || Thread.currentThread().isInterrupted()) {
                    response.cancel(true);
                    return failure(TaskResult.UNKNOWN, ProblemCode.CANCELLED, "VOICE_CANCELLED_AFTER_SEND", "The provider may have processed the cancelled audio");
                }
                long remaining = budget - (System.nanoTime() - start);
                if (remaining <= 0) {
                    response.cancel(true);
                    return failure(TaskResult.UNKNOWN, ProblemCode.TIMEOUT, "VOICE_TIMEOUT", "The provider outcome is unknown after the deadline");
                }
                try {
                    var result = response.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)), TimeUnit.NANOSECONDS);
                    byte[] bytes = result.body();
                    try {
                        if (result.statusCode() < 200 || result.statusCode() >= 300) {
                            boolean unsupported = false;
                            if (result.statusCode() == 400 || result.statusCode() == 422) {
                                try {
                                    var error = VoiceProtocol.JSON.readTree(bytes);
                                    unsupported = error != null && VoiceProtocol.unsupported(error.path("error"));
                                } catch (
                                    IOException invalidError) { /* Known HTTP rejection still applies. / 仍按已知 HTTP 拒绝处理。 */ }
                            }
                            return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(
                                unsupported ? ProblemCode.INCOMPATIBLE : result.statusCode() == 401 || result.statusCode() == 403
                                    ? ProblemCode.ACCESS_DENIED : ProblemCode.IO_FAILURE,
                                unsupported ? "VOICE_UNSUPPORTED" : "VOICE_HTTP_REJECTED", "The selected provider rejected the audio request",
                                Map.of("modelId", modelId, "httpStatus", Integer.toString(result.statusCode())), Optional.empty())));
                        }
                        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(VoiceProtocol.decode(bytes, modelId)), Optional.empty());
                    } catch (IOException invalid) {
                        return failure(TaskResult.FAILED, ProblemCode.CONTRACT_VIOLATION, "VOICE_RESPONSE_INVALID", "The transcription response is invalid");
                    } finally {
                        Arrays.fill(bytes, (byte) 0);
                    }
                } catch (TimeoutException waiting) { /* Recheck cancellation and deadline. / 重核取消及期限。 */ }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (response != null) response.cancel(true);
            return failure(TaskResult.UNKNOWN, ProblemCode.CANCELLED, "VOICE_INTERRUPTED", "The interrupted provider outcome is unknown");
        } catch (ExecutionException | RuntimeException lost) {
            return failure(TaskResult.UNKNOWN, ProblemCode.IO_FAILURE, "VOICE_TRANSPORT_UNKNOWN", "The provider outcome could not be established");
        }
    }

    /** Classifies a request failure without retaining provider content. / 分类请求失败，不保留提供方内容。
     * @param outcome actual send outcome / 实际发送结果
     * @param code stable failure category / 稳定失败类别
     * @param event safe event identity / 安全事件标识
     * @param message safe explanation / 安全说明
     * @return classified operation result / 已分类操作结果 */
    private OperationResult<VoiceResponse> failure(TaskResult outcome, ProblemCode code, String event, String message) {
        return VoiceProtocol.failure(outcome, code, event, message, modelId, Optional.empty());
    }

    /** Confirms actual client termination before releasing audio. / 确认实际客户端终止后释放音频。
     * @throws IOException if the client remains alive / 客户端仍活动时 */
    @Override
    public void close() throws IOException {
        synchronized (this) {
            closed = true;
        }
        if (response != null && !response.isDone()) response.cancel(true);
        boolean interrupted = Thread.interrupted();
        try {
            client.shutdownNow();
            if (!client.awaitTermination(Duration.ofSeconds(2)))
                throw new IOException("Voice client is still terminating");
            Arrays.fill(body, (byte) 0);
        } catch (InterruptedException failure) {
            interrupted = true;
            throw new IOException("Voice client termination interrupted");
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
