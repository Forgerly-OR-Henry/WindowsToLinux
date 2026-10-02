package gold.debug.windowstolinux.app.ai.voice;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import gold.debug.windowstolinux.app.ai.http.*;
import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.task.*;

/** One real multipart transcription request, never replayed after uncertainty. / 一个真实 multipart 转写请求，未知状态后不重放。 */
public final class VoiceHttpCall implements AutoCloseable {
    private final HttpClient client;
    private final HttpRequest request;
    private final byte[] body;
    private final String modelId;
    private final long start = System.nanoTime(), budget;
    private volatile CompletableFuture<HttpResponse<byte[]>> response;
    private boolean started, closed;

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
        ModelHttpCall.validateEndpoint(endpoint);
        VoiceRequest.validateTimeout(timeout);
        if (key == null || key.length == 0 || model == null || !model.matches("[A-Za-z0-9._:/-]{1,128}"))
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
        } finally { Arrays.fill(audio, (byte) 0); }
        request = HttpRequest.newBuilder(endpoint).timeout(timeout)
            .header("Authorization", "Bearer " + new String(key))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(timeout.compareTo(Duration.ofSeconds(10)) < 0 ? timeout : Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1).build();
    }

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
            return failure(Outcome.CANCELLED, ProblemCode.CANCELLED, "VOICE_CANCELLED_BEFORE_SEND", "Voice input was cancelled before sending");
        if (System.nanoTime() - start >= budget)
            return failure(Outcome.FAILED, ProblemCode.TIMEOUT, "VOICE_BEFORE_SEND_TIMEOUT", "Voice deadline ended before sending");
        try {
            response = client.sendAsync(request, ignored -> new LimitedHttpBodySubscriber(VoiceProtocol.MAX_RESPONSE_BYTES));
            for (;;) {
                if (cancellation.requested() || Thread.currentThread().isInterrupted()) {
                    response.cancel(true);
                    return failure(Outcome.UNKNOWN, ProblemCode.CANCELLED, "VOICE_CANCELLED_AFTER_SEND", "The provider may have processed the cancelled audio");
                }
                long remaining = budget - (System.nanoTime() - start);
                if (remaining <= 0) {
                    response.cancel(true);
                    return failure(Outcome.UNKNOWN, ProblemCode.TIMEOUT, "VOICE_TIMEOUT", "The provider outcome is unknown after the deadline");
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
                                } catch (IOException invalidError) { /* Known HTTP rejection still applies. / 仍按已知 HTTP 拒绝处理。 */ }
                            }
                            return new OperationResult<>(Outcome.FAILED, Optional.empty(), Optional.of(new Problem(
                                unsupported ? ProblemCode.INCOMPATIBLE : result.statusCode() == 401 || result.statusCode() == 403
                                    ? ProblemCode.ACCESS_DENIED : ProblemCode.IO_FAILURE,
                                unsupported ? "VOICE_UNSUPPORTED" : "VOICE_HTTP_REJECTED", "The selected provider rejected the audio request",
                                Map.of("modelId", modelId, "httpStatus", Integer.toString(result.statusCode())), Optional.empty())));
                        }
                        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(VoiceProtocol.decode(bytes, modelId)), Optional.empty());
                    } catch (IOException invalid) {
                        return failure(Outcome.FAILED, ProblemCode.CONTRACT_VIOLATION, "VOICE_RESPONSE_INVALID", "The transcription response is invalid");
                    } finally { Arrays.fill(bytes, (byte) 0); }
                } catch (TimeoutException waiting) { /* Recheck cancellation and deadline. / 重核取消及期限。 */ }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (response != null) response.cancel(true);
            return failure(Outcome.UNKNOWN, ProblemCode.CANCELLED, "VOICE_INTERRUPTED", "The interrupted provider outcome is unknown");
        } catch (ExecutionException | RuntimeException lost) {
            return failure(Outcome.UNKNOWN, ProblemCode.IO_FAILURE, "VOICE_TRANSPORT_UNKNOWN", "The provider outcome could not be established");
        }
    }

    private OperationResult<VoiceResponse> failure(Outcome outcome, ProblemCode code, String event, String message) {
        return VoiceProtocol.failure(outcome, code, event, message, modelId, Optional.empty());
    }

    /** Confirms actual client termination before releasing audio. / 确认实际客户端终止后释放音频。
     * @throws IOException if the client remains alive / 客户端仍活动时 */
    @Override public void close() throws IOException {
        synchronized (this) { closed = true; }
        if (response != null && !response.isDone()) response.cancel(true);
        boolean interrupted = Thread.interrupted();
        try {
            client.shutdownNow();
            if (!client.awaitTermination(Duration.ofSeconds(2))) throw new IOException("Voice client is still terminating");
            Arrays.fill(body, (byte) 0);
        } catch (InterruptedException failure) {
            interrupted = true; throw new IOException("Voice client termination interrupted");
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
