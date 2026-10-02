package gold.debug.windowstolinux.app.ai.voice;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.task.*;

/** OpenAI transcription session with ordered commits, bounded queues and explicit terminal cleanup. / 具有有序提交、有界队列及明确终态清理的 OpenAI 转写会话。 */
public final class VoiceSocketSession implements VoiceSession, WebSocket.Listener {
    /** Maximum aligned PCM bytes accepted per send. / 每次发送接收的对齐 PCM 字节上限。 */
    private static final int MAX_CHUNK = 65_536;

    /** Thirty-minute mono PCM16 session ceiling. / 三十分钟单声道 PCM16 会话上限。 */
    private static final long MAX_AUDIO = 24_000L * 2 * 60 * 30;

    /** Actual dedicated client retained until physical termination. / 保留至物理终止的实际专用客户端。 */
    private final HttpClient client;

    /** Frozen session deadline and queue limits. / 冻结会话时限和队列限制。 */
    private final VoiceOptions options;

    /** Serialized original-task transcript callback. / 串行原任务转写回调。 */
    private final VoiceListener listener;

    /** Original cooperative cancellation signal. / 原协作取消信号。 */
    private final CancelToken cancellation;

    /** Frozen non-secret platform model identity. / 冻结的不含秘密平台模型身份。 */
    private final String modelId;

    /** Frozen provider transcription model name. / 冻结提供方转写模型名称。 */
    private final String model;

    /** Monotonic start covering client construction and connection admission. / 覆盖客户端构造及连接准入的单调起点。 */
    private final long start = System.nanoTime();

    /** Exact protocol configuration acknowledgement. / 精确协议配置确认。 */
    private final CompletableFuture<Void> ready = new CompletableFuture<>();

    /** Classified outcome published after physical cleanup. / 物理清理后发布的分类结果。 */
    private final CompletableFuture<OperationResult<VoiceResponse>> completion = new CompletableFuture<>();

    /** Owned deadline and cancellation observer. / 所属期限及取消观测器。 */
    private final ScheduledExecutorService watchdog;

    /** Owned queued PCM copies cleared after actual sends or cancellation. / 实际发送或取消后清除的所属排队 PCM 副本。 */
    private final Set<byte[]> audioBuffers = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Bounded commit association and transcript state under this monitor. / 由此监视器保护的有界提交关联及转写状态。 */
    private final VoiceTranscript transcript = new VoiceTranscript();

    /** Current bounded fragmented JSON event. / 当前有界分片 JSON 事件。 */
    private final StringBuilder frame = new StringBuilder();

    /** Actual serialized socket-send tail. / 实际串行套接字发送尾端。 */
    private CompletableFuture<Void> outbound = CompletableFuture.completedFuture(null);

    /** Actual admitted socket, including opening failure cleanup. / 实际准入套接字，包括打开失败的清理。 */
    private volatile WebSocket socket;

    /** Actual handshake future retained for cancellation. / 为取消保留的实际握手 future。 */
    private volatile CompletableFuture<WebSocket> connecting;

    /** Active protocol callback thread used to avoid self-wait and verify drain. / 用于避免自等待并核实排空的活动协议回调线程。 */
    private volatile Thread callbackThread;

    /** Positive evidence that client, observer and callback have terminated. / 客户端、观测器及回调已终止的正面证据。 */
    private volatile boolean resourcesClosed;

    /** Input seal after finish admission. / finish 准入后的输入封闭。 */
    private boolean finishing;

    /** Classified terminal state suppressing further normal callbacks. / 阻止后续正常回调的分类终态。 */
    private boolean terminal;

    /** Positive evidence of audio admitted to the actual socket send. / 音频已准入实际套接字发送的正面证据。 */
    private boolean audioSent;

    /** Raw bytes queued for actual socket sends. / 等待实际套接字发送的原始字节。 */
    private int buffered;

    /** UTF-8 bytes accumulated for the current fragmented event. / 当前分片事件已累积的 UTF-8 字节。 */
    private int frameBytes;

    /** Accepted PCM bytes since the last input commit. / 最后输入提交后已接收的 PCM 字节。 */
    private long turnBytes;

    /** Complete-session accepted PCM bytes for the duration ceiling. / 用于时长上限的完整会话已接收 PCM 字节。 */
    private long totalBytes;

    /** Opens and verifies an actual transcription session configuration. / 打开并核实实际转写会话配置。
     * @param endpoint explicit configured realtime URL / 明确配置的实时地址
     * @param key temporary credential / 临时凭据
     * @param model configured provider transcription model / 已配置提供方转写模型
     * @param modelId platform selection / 平台所选身份
     * @param options finite buffering and deadline / 有限缓冲和时限
     * @param listener ordered callback / 有序回调
     * @param cancellation original cancellation / 原取消信号
     * @return ready session / 就绪会话
     * @throws ApiException if opening or configuration fails / 打开或配置失败时 */
    public static VoiceSocketSession open(URI endpoint, char[] key, String model, String modelId,
                                          VoiceOptions options, VoiceListener listener, CancelToken cancellation) throws ApiException {
        return open(endpoint, key, model, modelId, options, listener, cancellation, ignored -> {
        });
    }

    /** Records transport ownership before connection admission so failed cleanup remains recoverable. / 在连接准入前记录传输归属，使失败清理仍可恢复。
     * @param endpoint configured complete endpoint / 配置完整端点
     * @param key captured credential / 捕获凭据
     * @param model provider model / 提供方模型
     * @param modelId platform model identity / 平台模型身份
     * @param options finite limits / 有限限制
     * @param listener serialized observations / 串行观测
     * @param cancellation original cancellation / 原取消信号
     * @param retained host-owned transport registration / 宿主持有的传输登记
     * @return ready session / 就绪会话
     * @throws ApiException if opening or cleanup fails / 打开或清理失败时 */
    public static VoiceSocketSession open(URI endpoint, char[] key, String model, String modelId,
                                          VoiceOptions options, VoiceListener listener, CancelToken cancellation,
                                          java.util.function.Consumer<VoiceSocketSession> retained) throws ApiException {
        VoiceProtocol.validateEndpoint(endpoint);
        try {
            VoiceProtocol.validateKey(key);
        } catch (IllegalArgumentException invalid) {
            throw problem(ProblemCode.INVALID_INPUT, "VOICE_CONFIGURATION", "Invalid voice credential");
        }
        if (model == null || !model.matches("[A-Za-z0-9._:/-]{1,128}"))
            throw problem(ProblemCode.INVALID_INPUT, "VOICE_CONFIGURATION", "Invalid realtime model configuration");
        var session = new VoiceSocketSession(model, modelId, options, listener, cancellation);
        try {
            retained.accept(session);
            if (cancellation.requested())
                throw problem(ProblemCode.CANCELLED, "VOICE_CANCELLED_BEFORE_OPEN", "Realtime input was cancelled before opening");
            if (System.nanoTime() - session.start >= options.timeout().toNanos())
                throw problem(ProblemCode.TIMEOUT, "VOICE_REALTIME_TIMEOUT", "Realtime opening budget ended before connection admission");
            session.connecting = session.client.newWebSocketBuilder()
                .connectTimeout(options.timeout().compareTo(Duration.ofSeconds(10)) < 0 ? options.timeout() : Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + new String(key))
                .buildAsync(VoiceProtocol.transcriptionEndpoint(endpoint), session);
            session.connecting.whenComplete((connected, failure) -> {
                if (failure != null) {
                    Throwable cause = unwrap(failure);
                    if (cause instanceof WebSocketHandshakeException handshake) {
                        int status = handshake.getResponse().statusCode();
                        session.fail(Outcome.FAILED, status == 401 || status == 403 ? ProblemCode.ACCESS_DENIED : ProblemCode.UNAVAILABLE,
                            "VOICE_CONNECTION_REJECTED", "The provider rejected the realtime connection");
                    } else
                        session.fail(Outcome.FAILED, ProblemCode.IO_FAILURE, "VOICE_CONNECTION_FAILED", "Realtime connection could not be established");
                }
            });
            session.awaitReady();
            return session;
        } catch (ApiException failure) {
            session.fail(Outcome.FAILED, failure.problem().code(), failure.problem().eventCode(), failure.problem().message());
            session.awaitCleanup();
            throw failure;
        } catch (RuntimeException invalid) {
            var failure = problem(ProblemCode.INVALID_INPUT, "VOICE_CONFIGURATION", "Realtime connection configuration is invalid");
            session.fail(Outcome.FAILED, failure.problem().code(), failure.problem().eventCode(), failure.problem().message());
            session.awaitCleanup();
            throw failure;
        }
    }

    /** Allocates the original bounded transport and cancellation observer. / 分配原有界传输及取消观测器。
     * @param model frozen provider model / 冻结提供方模型
     * @param modelId frozen platform identity / 冻结平台身份
     * @param options validated finite limits / 已校验有限限制
     * @param listener serialized observations / 串行观测
     * @param cancellation original cancellation / 原取消信号 */
    private VoiceSocketSession(String model, String modelId, VoiceOptions options,
                               VoiceListener listener, CancelToken cancellation) {
        this.model = model;
        this.modelId = Objects.requireNonNull(modelId);
        this.options = Objects.requireNonNull(options);
        this.listener = Objects.requireNonNull(listener);
        this.cancellation = Objects.requireNonNull(cancellation);
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10)).build();
        watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "voice-session-deadline");
            thread.setDaemon(true);
            return thread;
        });
        watchdog.scheduleWithFixedDelay(() -> {
            if (cancellation.requested()) cancelTransport();
            else if (System.nanoTime() - start >= options.timeout().toNanos())
                fail(audioWasSent() ? Outcome.UNKNOWN : Outcome.FAILED, ProblemCode.TIMEOUT,
                    "VOICE_REALTIME_TIMEOUT", "Realtime input ended without all confirmed results before the deadline");
        }, 0, 25, TimeUnit.MILLISECONDS);
    }

    /** Waits for exact protocol acknowledgement within the remaining original deadline. / 在原剩余期限内等待精确协议确认。
     * @throws ApiException if opening, acknowledgement or actual cleanup fails / 打开、确认或实际清理失败时 */
    private void awaitReady() throws ApiException {
        for (; ; ) {
            if (cancellation.requested()) {
                cancelTransport();
                throw problem(ProblemCode.CANCELLED, "VOICE_OPEN_CANCELLED", "Realtime opening was cancelled");
            }
            try {
                ready.get(25, TimeUnit.MILLISECONDS);
                return;
            } catch (TimeoutException waiting) {
                // Poll cancellation again; the watchdog fails ready at the original deadline.
                // 再次轮询取消；看门狗在原截止时间使 ready 失败，25 ms 未就绪不是终态。
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw problem(ProblemCode.CANCELLED, "VOICE_OPEN_INTERRUPTED", "Realtime opening was interrupted");
            } catch (ExecutionException failure) {
                var result = awaitCleanup();
                throw new ApiException(result.problem().orElseGet(() -> new Problem(ProblemCode.CANCELLED,
                    "VOICE_OPEN_CANCELLED", "Realtime opening was cancelled", Map.of(), Optional.empty())));
            }
        }
    }

    /** Accepts the actual socket and sends the current GA transcription schema. / 接收实际套接字并发送当前正式转写结构。
     * @param webSocket actual admitted socket / 实际准入套接字 */
    @Override
    public void onOpen(WebSocket webSocket) {
        synchronized (this) {
            socket = webSocket;
            if (terminal) {
                webSocket.abort();
                return;
            }
            var event = VoiceProtocol.JSON.createObjectNode();
            event.put("type", "session.update");
            var session = event.putObject("session").put("type", "transcription");
            var input = session.putObject("audio").putObject("input");
            input.putObject("format").put("type", "audio/pcm").put("rate", 24000);
            input.putObject("transcription").put("model", model);
            input.putNull("turn_detection");
            enqueue(event.toString(), null);
            webSocket.request(1);
        }
    }

    /** Consumes bounded text fragments one at a time. / 逐次消费有界文字片段。
     * @param webSocket delivering socket / 投递套接字
     * @param data bounded current fragment / 有界当前分片
     * @param last complete-event marker / 完整事件标记
     * @return acknowledgement after synchronous fragment processing / 同步分片处理后的确认 */
    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        callbackThread = Thread.currentThread();
        try {
            synchronized (this) {
                if (terminal) return CompletableFuture.completedFuture(null);
                if (data.length() > VoiceProtocol.MAX_RESPONSE_BYTES - frame.length()) {
                    fail(Outcome.UNKNOWN, ProblemCode.CONTRACT_VIOLATION, "VOICE_EVENT_LIMIT", "Realtime event exceeded its bound");
                    return CompletableFuture.completedFuture(null);
                }
                int received = StandardCharsets.UTF_8.encode(CharBuffer.wrap(data)).remaining();
                if (received > VoiceProtocol.MAX_RESPONSE_BYTES - frameBytes) {
                    fail(Outcome.UNKNOWN, ProblemCode.CONTRACT_VIOLATION, "VOICE_EVENT_LIMIT", "Realtime event exceeded its UTF-8 byte bound");
                    return CompletableFuture.completedFuture(null);
                }
                frameBytes += received;
                frame.append(data);
                if (last) {
                    if (StandardCharsets.UTF_8.encode(CharBuffer.wrap(frame)).remaining() > VoiceProtocol.MAX_RESPONSE_BYTES) {
                        fail(Outcome.UNKNOWN, ProblemCode.CONTRACT_VIOLATION, "VOICE_EVENT_LIMIT", "Realtime event exceeded its complete UTF-8 byte bound");
                        return CompletableFuture.completedFuture(null);
                    }
                    try {
                        event(VoiceProtocol.JSON.readTree(frame.toString()));
                    } catch (IOException | IllegalArgumentException invalid) {
                        fail(audioSent ? Outcome.UNKNOWN : Outcome.FAILED, ProblemCode.CONTRACT_VIOLATION,
                            "VOICE_EVENT_INVALID", "Realtime event did not satisfy the transcription protocol");
                    } finally {
                        frame.setLength(0);
                        frameBytes = 0;
                    }
                }
                if (!terminal) webSocket.request(1);
            }
        } finally {
            callbackThread = null;
        }
        return CompletableFuture.completedFuture(null);
    }

    /** Verifies session acknowledgements and associates only committed transcript parts. / 核实会话确认，仅关联已提交转写片段。
     * @param event bounded parsed provider event / 有界已解析提供方事件
     * @throws IOException if the event violates the frozen protocol / 事件违反冻结协议时 */
    private void event(JsonNode event) throws IOException {
        if (event == null || !event.isObject() || !event.path("type").isTextual())
            throw new IOException("Missing event type");
        String type = event.path("type").textValue();
        if (type.equals("session.created")) return;
        if (type.equals("session.updated")) {
            var session = event.path("session");
            var input = session.path("audio").path("input");
            var rate = input.path("format").path("rate");
            var configuredModel = input.path("transcription").path("model");
            if (!session.path("type").isTextual() || !session.path("type").textValue().equals("transcription")
                || !input.path("format").path("type").isTextual()
                || !input.path("format").path("type").textValue().equals("audio/pcm")
                || !rate.isIntegralNumber() || !rate.canConvertToInt() || rate.intValue() != 24000
                || !configuredModel.isTextual() || !configuredModel.textValue().equals(model)
                || !input.path("turn_detection").isNull()) throw new IOException("Session configuration mismatched");
            ready.complete(null);
            return;
        }
        if (type.equals("error") || type.equals("conversation.item.input_audio_transcription.failed")) {
            String errorType = event.path("error").path("type").asText();
            boolean unsupported = VoiceProtocol.unsupported(event.path("error"));
            fail(Outcome.FAILED, unsupported ? ProblemCode.INCOMPATIBLE : errorType.equals("authentication_error")
                    ? ProblemCode.ACCESS_DENIED : ProblemCode.CONTRACT_VIOLATION,
                unsupported ? "VOICE_UNSUPPORTED" : "VOICE_PROTOCOL_REJECTED", "The provider rejected the realtime transcription operation");
            return;
        }
        if (type.equals("input_audio_buffer.committed")) {
            transcript.acknowledge(event);
            return;
        }
        if (type.equals("conversation.item.input_audio_transcription.delta")
            || type.equals("conversation.item.input_audio_transcription.completed")) {
            if (!ready.isDone()) throw new IOException("Transcription session is not ready");
            var observation = transcript.accept(event, type.endsWith("completed"));
            if (observation == null) return;
            if (!terminal && !cancellation.requested()) {
                try {
                    listener.onEvent(observation);
                } catch (RuntimeException failure) {
                    fail(Outcome.UNKNOWN, ProblemCode.CONTRACT_VIOLATION, "VOICE_LISTENER_FAILED", "The transcript listener failed");
                    return;
                }
            }
            completeIfReady();
        }
    }

    /** Rejects binary input in this JSON transcription protocol. / 拒绝 JSON 转写协议中的二进制输入。
     * @param webSocket delivering socket / 投递套接字
     * @param data rejected binary fragment / 拒绝的二进制分片
     * @param last complete-message marker / 完整消息标记
     * @return acknowledgement after classified termination / 分类终止后的确认 */
    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, java.nio.ByteBuffer data, boolean last) {
        fail(Outcome.UNKNOWN, ProblemCode.CONTRACT_VIOLATION, "VOICE_BINARY_EVENT", "Unexpected binary provider event");
        return CompletableFuture.completedFuture(null);
    }

    /** Classifies premature close without replaying audio. / 分类提前关闭，不重放音频。
     * @param webSocket closed actual socket / 已关闭实际套接字
     * @param statusCode provider close code / 提供方关闭码
     * @param reason provider text discarded from diagnostics / 从诊断中丢弃的提供方文字
     * @return acknowledgement after classification / 分类后的确认 */
    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        fail(audioWasSent() ? Outcome.UNKNOWN : Outcome.FAILED, ProblemCode.IO_FAILURE,
            "VOICE_DISCONNECTED", "Realtime connection ended before completion");
        return CompletableFuture.completedFuture(null);
    }

    /** Classifies transport loss without retaining provider error text. / 分类传输丢失，不保留提供方错误文字。
     * @param webSocket failed actual socket / 失败的实际套接字
     * @param error transport cause discarded from public diagnostics / 从公开诊断中丢弃的传输原因 */
    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        fail(audioWasSent() ? Outcome.UNKNOWN : Outcome.FAILED, ProblemCode.IO_FAILURE,
            "VOICE_DISCONNECTED", "Realtime connection failed before completion");
    }

    /** Enqueues sample-aligned PCM without exceeding the queue. / 排队样本对齐 PCM，不超过队列。
     * @param audio copied mono PCM16 input / 复制的单声道 PCM16 输入
     * @return actual serialized send completion / 实际串行发送完成
     * @throws ApiException if input, state, cancellation or bounds reject admission / 输入、状态、取消或限制拒绝准入时 */
    @Override
    public synchronized CompletionStage<Void> send(byte[] audio) throws ApiException {
        active();
        Objects.requireNonNull(audio);
        if (audio.length == 0 || audio.length > MAX_CHUNK || (audio.length & 1) != 0)
            throw problem(ProblemCode.INVALID_INPUT, "VOICE_AUDIO_CHUNK", "Audio chunks must contain aligned PCM16 samples up to 64 KiB");
        if (audio.length > options.maxBufferedBytes() - buffered)
            throw problem(ProblemCode.BUSY, "VOICE_BACKPRESSURE", "The bounded voice send queue is full; no audio was accepted");
        if (audio.length > MAX_AUDIO - totalBytes)
            throw problem(ProblemCode.INVALID_INPUT, "VOICE_AUDIO_LIMIT", "Realtime audio exceeds 30 minutes");
        byte[] copy = audio.clone();
        audioBuffers.add(copy);
        buffered += copy.length;
        turnBytes += copy.length;
        totalBytes += copy.length;
        return enqueue(null, copy).minimalCompletionStage();
    }

    /** Commits a complete turn without sealing further input. / 提交完整轮次，不封闭后续输入。
     * @return actual commit send completion / 实际提交发送完成
     * @throws ApiException if the session or current turn rejects commit / 会话或当前轮次拒绝提交时 */
    @Override
    public synchronized CompletionStage<Void> commit() throws ApiException {
        active();
        return commitTurn().minimalCompletionStage();
    }

    /** Assigns the original input ordinal and sends one valid commit. / 分配原输入序号并发送一次有效提交。
     * @return actual commit send future / 实际提交发送 future
     * @throws ApiException if audio duration or pending-turn bounds reject commit / 音频时长或待定稿轮次限制拒绝提交时 */
    private CompletableFuture<Void> commitTurn() throws ApiException {
        if (turnBytes < 4800)
            throw problem(ProblemCode.INVALID_INPUT, "VOICE_TURN_TOO_SHORT", "Each committed audio turn must contain at least 100 ms");
        if (!transcript.canCommit())
            throw problem(ProblemCode.BUSY, "VOICE_TURN_BACKPRESSURE", "The bounded pending-turn queue is full");
        transcript.commit();
        turnBytes = 0;
        return enqueue("{\"type\":\"input_audio_buffer.commit\"}", null);
    }

    /** Serializes one protocol event or owned audio chunk and clears sent PCM copies. / 串行化一个协议事件或所属音频块，并清除已发送 PCM 副本。
     * @param event complete JSON event, absent for PCM / 完整 JSON 事件，PCM 时为空
     * @param audio owned queued PCM, absent for JSON / 所属排队 PCM，JSON 时为空
     * @return actual send future / 实际发送 future */
    private CompletableFuture<Void> enqueue(String event, byte[] audio) {
        var next = outbound.thenCompose(ignored -> {
            synchronized (this) {
                if (terminal) return CompletableFuture.failedFuture(new CancellationException("Voice session ended"));
                String text = event;
                if (audio != null) {
                    audioSent = true;
                    text = "{\"type\":\"input_audio_buffer.append\",\"audio\":\"" + Base64.getEncoder().encodeToString(audio) + "\"}";
                }
                return socket.sendText(text, true).thenApply(sent -> (Void) null);
            }
        });
        outbound = next;
        next.whenComplete((ignored, failure) -> {
            synchronized (this) {
                if (audio != null) {
                    Arrays.fill(audio, (byte) 0);
                    audioBuffers.remove(audio);
                    buffered -= audio.length;
                }
                if (failure != null && !terminal)
                    fail(audioSent ? Outcome.UNKNOWN : Outcome.FAILED, ProblemCode.IO_FAILURE,
                        "VOICE_SEND_UNKNOWN", "The realtime send outcome could not be established");
                else completeIfReady();
            }
        });
        return next;
    }

    /** Seals input and awaits all committed final results. / 封闭输入并等待全部已提交最终结果。
     * @return classified completion after actual cleanup / 实际清理后的分类完成
     * @throws ApiException if the session, cancellation or remaining turn is invalid / 会话、取消或剩余轮次无效时 */
    @Override
    public synchronized CompletionStage<OperationResult<VoiceResponse>> finish() throws ApiException {
        if (finishing || terminal) return completion();
        active();
        if (turnBytes > 0) commitTurn();
        if (!transcript.hasInput())
            throw problem(ProblemCode.INVALID_INPUT, "VOICE_EMPTY_INPUT", "No audio turn was committed");
        finishing = true;
        completeIfReady();
        return completion();
    }

    /** Ends successful input only after every committed final and actual send are confirmed. / 仅在全部提交定稿及实际发送均已确认后成功结束输入。 */
    private void completeIfReady() {
        if (!terminal && finishing && transcript.complete()
            && outbound.isDone() && !outbound.isCompletedExceptionally())
            terminate(new OperationResult<>(Outcome.SUCCEEDED, Optional.of(confirmed()), Optional.empty()), true);
    }

    /** Guards original input admission and observes cooperative cancellation. / 保护原输入准入并观测协作取消。
     * @throws ApiException if input is sealed, unready or cancelled / 输入已封闭、未就绪或已取消时 */
    private void active() throws ApiException {
        if (terminal || finishing)
            throw problem(ProblemCode.UNAVAILABLE, "VOICE_SESSION_ENDED", "The session no longer accepts audio");
        if (!ready.isDone() || ready.isCompletedExceptionally())
            throw problem(ProblemCode.UNAVAILABLE, "VOICE_SESSION_NOT_READY", "Realtime transcription configuration is not ready");
        if (cancellation.requested()) {
            cancelTransport();
            throw problem(ProblemCode.CANCELLED, "VOICE_CANCELLED", "Realtime input was cancelled");
        }
    }

    /** Observes possible provider audio effects under the session monitor. / 在会话监视器下观察可能的提供方音频影响。
     * @return whether an audio send was admitted to the socket / 音频发送是否已准入套接字 */
    private synchronized boolean audioWasSent() {
        return audioSent;
    }

    /** Takes an immutable snapshot of already confirmed transcript parts. / 取得已确认转写片段的不可变快照。
     * @return input-ordered confirmed text / 按输入顺序的已确认文字 */
    private synchronized VoiceResponse confirmed() {
        return transcript.confirmed(modelId);
    }

    /** Terminates with safe classified evidence and preserves confirmed text. / 使用安全分类证据终止，并保留已确认文字。
     * @param outcome actual observed outcome / 实际观察结果
     * @param code stable failure category / 稳定失败类别
     * @param event safe event identity / 安全事件身份
     * @param message safe explanation / 安全说明 */
    private void fail(Outcome outcome, ProblemCode code, String event, String message) {
        synchronized (this) {
            terminate(VoiceProtocol.failure(outcome, code, event, message, modelId,
                !transcript.hasConfirmed() ? Optional.empty() : Optional.of(confirmed())), false);
        }
    }

    /** Seals input without replay or implicit commit, retaining uncertain external effects. / 封闭输入而不重放或隐式提交，保留未知外部影响。 */
    private void cancelTransport() {
        synchronized (this) {
            terminate(VoiceProtocol.failure(audioSent ? Outcome.UNKNOWN : Outcome.CANCELLED, ProblemCode.CANCELLED,
                "VOICE_CANCELLED", audioSent ? "Cancelled audio may have been processed; confirmed parts are retained"
                    : "Realtime input was cancelled before audio was sent", modelId,
                !transcript.hasConfirmed() ? Optional.empty() : Optional.of(confirmed())), false);
        }
    }

    /** Starts bounded physical cleanup once and publishes its actual outcome afterwards. / 仅启动一次有界物理清理，随后发布实际结果。
     * @param result classified protocol result / 已分类协议结果
     * @param graceful whether final input allows an orderly close attempt / 定稿输入是否允许尝试有序关闭 */
    private void terminate(OperationResult<VoiceResponse> result, boolean graceful) {
        if (terminal) return;
        terminal = true;
        finishing = true;
        frame.setLength(0);
        transcript.clearPartial();
        for (var bytes : audioBuffers) Arrays.fill(bytes, (byte) 0);
        audioBuffers.clear();
        ready.completeExceptionally(new IOException("Voice session terminated"));
        var cleanup = new Thread(() -> {
            boolean interrupted = false;
            OperationResult<VoiceResponse> actual = result;
            try {
                var pending = connecting;
                if (pending != null && !pending.isDone()) pending.cancel(true);
                var connection = socket;
                if (connection != null) {
                    if (graceful) {
                        try {
                            connection.sendClose(WebSocket.NORMAL_CLOSURE, "finished").get(500, TimeUnit.MILLISECONDS);
                        } catch (
                            Exception ignored) { /* Abort below still verifies physical shutdown after a refused graceful close. / 有序关闭被拒后，下方 abort 仍核实物理终止。 */ }
                    }
                    connection.abort();
                }
                watchdog.shutdownNow();
                if (!watchdog.awaitTermination(2, TimeUnit.SECONDS))
                    throw new IOException("Voice deadline thread remains active");
                awaitClientClose();
            } catch (InterruptedException failure) {
                interrupted = true;
                actual = VoiceProtocol.failure(Outcome.UNKNOWN, ProblemCode.UNKNOWN, "VOICE_CLOSE_UNKNOWN", "Voice cleanup was interrupted", modelId, result.value());
            } catch (IOException failure) {
                actual = VoiceProtocol.failure(Outcome.UNKNOWN, ProblemCode.UNKNOWN, "VOICE_CLOSE_UNKNOWN", "Voice connection cleanup could not be confirmed", modelId, result.value());
            } finally {
                completion.complete(actual);
                if (interrupted) Thread.currentThread().interrupt();
            }
        }, "voice-session-close");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    /** Returns observation-only terminal completion. / 返回仅可观察的终态完成。
     * @return actual classified result after cleanup / 清理后的实际分类结果 */
    @Override
    public CompletionStage<OperationResult<VoiceResponse>> completion() {
        return completion.minimalCompletionStage();
    }

    /** Confirms physical client and deadline-worker termination, including a successful cleanup retry. / 确认实际客户端及期限工作线程终止，包括成功的清理重试。
     * @return whether physical resource termination was established / 是否已证实物理资源终止 */
    public boolean resourcesClosed() {
        return resourcesClosed;
    }

    /** Cancels input and waits for cleanup outside provider callbacks. / 取消输入，在提供方回调外等待清理。
     * @throws ApiException if physical cleanup cannot be confirmed / 物理清理无法确认时 */
    @Override
    public void cancel() throws ApiException {
        cancelTransport();
        if (Thread.currentThread() != callbackThread) awaitCleanup();
    }

    /** Cancels rather than committing an unfinished turn. / 取消而非提交未结束轮次。
     * @throws ApiException if original cleanup and its retry cannot be confirmed / 原清理及其重试无法确认时 */
    @Override
    public void close() throws ApiException {
        cancelTransport();
        if (Thread.currentThread() == callbackThread) return;
        try {
            awaitCleanup();
        } catch (ApiException failure) {
            if (!failure.problem().eventCode().equals("VOICE_CLOSE_UNKNOWN")) throw failure;
            retryPhysicalClose();
        }
    }

    /** Retries actual termination while preserving interruption and original task ownership. / 保留中断及原任务归属，同时重试实际终止。
     * @throws ApiException if client, deadline observer or callback remains active / 客户端、期限观测器或回调仍活动时 */
    private void retryPhysicalClose() throws ApiException {
        boolean interrupted = Thread.interrupted();
        try {
            if (connecting != null && !connecting.isDone()) connecting.cancel(true);
            if (socket != null) socket.abort();
            watchdog.shutdownNow();
            if (!watchdog.awaitTermination(2, TimeUnit.SECONDS))
                throw problem(ProblemCode.UNKNOWN, "VOICE_CLOSE_UNKNOWN", "Voice resources remain active after cleanup retry");
            awaitClientClose();
        } catch (InterruptedException failure) {
            interrupted = true;
            throw problem(ProblemCode.UNKNOWN, "VOICE_CLOSE_UNKNOWN", "Voice cleanup retry was interrupted");
        } catch (IOException failure) {
            throw problem(ProblemCode.UNKNOWN, "VOICE_CLOSE_UNKNOWN", "Voice client or callback remains active after cleanup retry");
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Confirms client and callback drain within one shared physical-close budget. / 在同一物理关闭预算内确认客户端及回调排空。
     * @throws IOException if termination remains unconfirmed / 终止仍未确认时
     * @throws InterruptedException if physical cleanup is interrupted / 物理清理被中断时 */
    private void awaitClientClose() throws IOException, InterruptedException {
        client.shutdownNow();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        if (!client.awaitTermination(Duration.ofSeconds(2)))
            throw new IOException("Voice socket client remains active");
        while (callbackThread != null) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IOException("Voice callback remains active");
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)));
        }
        resourcesClosed = true;
    }

    /** Observes bounded cleanup without converting interrupted shutdown into known success. / 观察有界清理，不将中断关闭转为已知成功。
     * @return actual terminal evidence / 实际终态证据
     * @throws ApiException if physical cleanup remains unconfirmed / 物理清理仍未确认时 */
    private OperationResult<VoiceResponse> awaitCleanup() throws ApiException {
        boolean interrupted = Thread.interrupted();
        try {
            var result = completion.get(6, TimeUnit.SECONDS);
            if (result.problem().map(Problem::eventCode).filter("VOICE_CLOSE_UNKNOWN"::equals).isPresent())
                throw new ApiException(result.problem().orElseThrow());
            return result;
        } catch (InterruptedException failure) {
            interrupted = true;
            throw problem(ProblemCode.UNKNOWN, "VOICE_CLOSE_UNKNOWN", "Voice cleanup was interrupted");
        } catch (ExecutionException | TimeoutException failure) {
            throw problem(ProblemCode.UNKNOWN, "VOICE_CLOSE_UNKNOWN", "Voice cleanup could not be confirmed");
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Removes future wrappers for safe transport classification. / 移除 future 包装以安全分类传输结果。
     * @param failure actual asynchronous cause / 实际异步原因
     * @return underlying transport cause / 底层传输原因 */
    private static Throwable unwrap(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null)
            failure = failure.getCause();
        return failure;
    }

    /** Builds public evidence without provider content or credentials. / 构造不含提供方内容或凭据的公开证据。
     * @param code stable category / 稳定类别
     * @param event stable event / 稳定事件
     * @param message safe explanation / 安全说明
     * @return classified public failure / 已分类公开失败 */
    private static ApiException problem(ProblemCode code, String event, String message) {
        return new ApiException(new Problem(code, event, message, Map.of(), Optional.empty()));
    }
}
