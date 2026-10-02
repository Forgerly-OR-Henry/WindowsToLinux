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
    private static final int MAX_CHUNK = 65_536, MAX_TURNS = 2048, MAX_PENDING_TURNS = 64;
    private static final long MAX_AUDIO = 24_000L * 2 * 60 * 30;
    private final HttpClient client;
    private final VoiceOptions options;
    private final VoiceListener listener;
    private final CancelToken cancellation;
    private final String modelId, model;
    private final long start = System.nanoTime();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CompletableFuture<OperationResult<VoiceResponse>> completion = new CompletableFuture<>();
    private final ScheduledExecutorService watchdog;
    private final Set<byte[]> audioBuffers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ArrayDeque<Long> unacknowledged = new ArrayDeque<>();
    private final Map<String, Long> orders = new HashMap<>();
    private final Map<String, StringBuilder> partials = new HashMap<>();
    private final TreeMap<Long, VoiceEvent> finals = new TreeMap<>();
    private final StringBuilder frame = new StringBuilder();
    private CompletableFuture<Void> outbound = CompletableFuture.completedFuture(null);
    private volatile WebSocket socket;
    private volatile CompletableFuture<WebSocket> connecting;
    private volatile Thread callbackThread;
    private boolean finishing, terminal, audioSent;
    private int buffered, finalCharacters, frameBytes, partialCharacters;
    private long turnBytes, totalBytes, committed, sequence;
    private String previousItem;

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
        return open(endpoint, key, model, modelId, options, listener, cancellation, ignored -> { });
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
        if (key == null || key.length == 0 || model == null || !model.matches("[A-Za-z0-9._:/-]{1,128}"))
            throw problem(ProblemCode.INVALID_INPUT, "VOICE_CONFIGURATION", "Invalid realtime model configuration");
        var session = new VoiceSocketSession(model, modelId, options, listener, cancellation);
        retained.accept(session);
        try {
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
                    } else session.fail(Outcome.FAILED, ProblemCode.IO_FAILURE, "VOICE_CONNECTION_FAILED", "Realtime connection could not be established");
                }
            });
            session.awaitReady();
            return session;
        } catch (ApiException failure) {
            session.fail(Outcome.FAILED, failure.problem().code(), failure.problem().eventCode(), failure.problem().message());
            session.awaitCleanup();
            throw failure;
        }
    }

    private VoiceSocketSession(String model, String modelId, VoiceOptions options,
        VoiceListener listener, CancelToken cancellation) {
        this.model = model; this.modelId = Objects.requireNonNull(modelId);
        this.options = Objects.requireNonNull(options); this.listener = Objects.requireNonNull(listener);
        this.cancellation = Objects.requireNonNull(cancellation);
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10)).build();
        watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "voice-session-deadline"); thread.setDaemon(true); return thread;
        });
        watchdog.scheduleWithFixedDelay(() -> {
            if (cancellation.requested()) cancelTransport();
            else if (System.nanoTime() - start >= options.timeout().toNanos())
                fail(audioWasSent() ? Outcome.UNKNOWN : Outcome.FAILED, ProblemCode.TIMEOUT,
                    "VOICE_REALTIME_TIMEOUT", "Realtime input ended without all confirmed results before the deadline");
        }, 0, 25, TimeUnit.MILLISECONDS);
    }

    private void awaitReady() throws ApiException {
        for (;;) {
            if (cancellation.requested()) {
                cancelTransport();
                throw problem(ProblemCode.CANCELLED, "VOICE_OPEN_CANCELLED", "Realtime opening was cancelled");
            }
            try { ready.get(25, TimeUnit.MILLISECONDS); return; }
            catch (TimeoutException waiting) { }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw problem(ProblemCode.CANCELLED, "VOICE_OPEN_INTERRUPTED", "Realtime opening was interrupted");
            } catch (ExecutionException failure) {
                var result = awaitCleanup();
                throw new ApiException(result.problem().orElseGet(() -> new Problem(ProblemCode.CANCELLED,
                    "VOICE_OPEN_CANCELLED", "Realtime opening was cancelled", Map.of(), Optional.empty())));
            }
        }
    }

    /** Accepts the actual socket and sends the current GA transcription schema. / 接收实际套接字并发送当前正式转写结构。 */
    @Override public void onOpen(WebSocket webSocket) {
        synchronized (this) {
            socket = webSocket;
            if (terminal) { webSocket.abort(); return; }
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

    /** Consumes bounded text fragments one at a time. / 逐次消费有界文字片段。 */
    @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
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
                    try { event(VoiceProtocol.JSON.readTree(frame.toString())); }
                    catch (IOException | IllegalArgumentException invalid) {
                        fail(audioSent ? Outcome.UNKNOWN : Outcome.FAILED, ProblemCode.CONTRACT_VIOLATION,
                            "VOICE_EVENT_INVALID", "Realtime event did not satisfy the transcription protocol");
                    } finally { frame.setLength(0); frameBytes = 0; }
                }
                if (!terminal) webSocket.request(1);
            }
        } finally { callbackThread = null; }
        return CompletableFuture.completedFuture(null);
    }

    private void event(JsonNode event) throws IOException {
        if (event == null || !event.isObject() || !event.path("type").isTextual()) throw new IOException("Missing event type");
        String type = event.path("type").textValue();
        if (type.equals("session.created")) return;
        if (type.equals("session.updated")) {
            var session = event.path("session");
            var input = session.path("audio").path("input");
            if (!session.path("type").asText().equals("transcription")
                || !input.path("format").path("type").asText().equals("audio/pcm")
                || input.path("format").path("rate").asInt() != 24000
                || !input.path("transcription").path("model").asText().equals(model)
                || !input.path("turn_detection").isNull()) throw new IOException("Session configuration mismatched");
            ready.complete(null); return;
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
            String id = text(event, "item_id");
            if (orders.containsKey(id) || unacknowledged.isEmpty()) throw new IOException("Unexpected commit acknowledgement");
            var previous = event.get("previous_item_id");
            if (previous != null && !previous.isNull() && !Objects.equals(previousItem, previous.asText()))
                throw new IOException("Commit predecessor mismatched");
            orders.put(id, unacknowledged.removeFirst()); previousItem = id; return;
        }
        if (type.equals("conversation.item.input_audio_transcription.delta")
            || type.equals("conversation.item.input_audio_transcription.completed")) {
            if (!ready.isDone() || !event.path("content_index").isIntegralNumber()
                || event.path("content_index").asInt(-1) != 0) throw new IOException("Unsupported transcript part");
            String id = text(event, "item_id");
            Long order = orders.get(id);
            if (order == null) throw new IOException("Transcript has no committed input");
            boolean last = type.endsWith("completed");
            String value = text(event, last ? "transcript" : "delta");
            var prior = finals.get(order);
            if (prior != null) {
                if (last && prior.text().equals(value)) return;
                throw new IOException("Finalized part changed");
            }
            if (value.length() > 262_144) throw new IOException("Transcript part exceeded bound");
            if (last) {
                if (value.length() > VoiceProtocol.MAX_RESPONSE_BYTES - finalCharacters) throw new IOException("Final transcript exceeded bound");
                finalCharacters += value.length();
                var partial = partials.remove(id);
                if (partial != null) partialCharacters -= partial.length();
            } else {
                var partial = partials.computeIfAbsent(id, ignored -> new StringBuilder());
                if (value.length() > 262_144 - partial.length()
                    || value.length() > VoiceProtocol.MAX_RESPONSE_BYTES - partialCharacters)
                    throw new IOException("Partial transcript exceeded bound");
                partial.append(value); partialCharacters += value.length();
            }
            var observation = new VoiceEvent(id, 0, sequence++, order, last, value);
            if (last) finals.put(order, observation);
            if (!terminal && !cancellation.requested()) {
                try { listener.onEvent(observation); }
                catch (RuntimeException failure) {
                    fail(Outcome.UNKNOWN, ProblemCode.CONTRACT_VIOLATION, "VOICE_LISTENER_FAILED", "The transcript listener failed"); return;
                }
            }
            completeIfReady();
        }
    }

    private static String text(JsonNode node, String field) throws IOException {
        var value = node.get(field);
        if (value == null || !value.isTextual()) throw new IOException("Missing event field");
        String text = value.textValue();
        if (field.equals("item_id") && (text.isBlank() || text.length() > 256)) throw new IOException("Invalid item identity");
        return text;
    }

    /** Rejects binary input in this JSON transcription protocol. / 拒绝 JSON 转写协议中的二进制输入。 */
    @Override public CompletionStage<?> onBinary(WebSocket webSocket, java.nio.ByteBuffer data, boolean last) {
        fail(Outcome.UNKNOWN, ProblemCode.CONTRACT_VIOLATION, "VOICE_BINARY_EVENT", "Unexpected binary provider event");
        return CompletableFuture.completedFuture(null);
    }
    /** Classifies premature close without replaying audio. / 分类提前关闭，不重放音频。 */
    @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        fail(audioWasSent() ? Outcome.UNKNOWN : Outcome.FAILED, ProblemCode.IO_FAILURE,
            "VOICE_DISCONNECTED", "Realtime connection ended before completion");
        return CompletableFuture.completedFuture(null);
    }
    /** Classifies transport loss without retaining provider error text. / 分类传输丢失，不保留提供方错误文字。 */
    @Override public void onError(WebSocket webSocket, Throwable error) {
        fail(audioWasSent() ? Outcome.UNKNOWN : Outcome.FAILED, ProblemCode.IO_FAILURE,
            "VOICE_DISCONNECTED", "Realtime connection failed before completion");
    }

    /** Enqueues sample-aligned PCM without exceeding the queue. / 排队样本对齐 PCM，不超过队列。 */
    @Override public synchronized CompletionStage<Void> send(byte[] audio) throws ApiException {
        active(); Objects.requireNonNull(audio);
        if (audio.length == 0 || audio.length > MAX_CHUNK || (audio.length & 1) != 0)
            throw problem(ProblemCode.INVALID_INPUT, "VOICE_AUDIO_CHUNK", "Audio chunks must contain aligned PCM16 samples up to 64 KiB");
        if (audio.length > options.maxBufferedBytes() - buffered)
            throw problem(ProblemCode.BUSY, "VOICE_BACKPRESSURE", "The bounded voice send queue is full; no audio was accepted");
        if (audio.length > MAX_AUDIO - totalBytes)
            throw problem(ProblemCode.INVALID_INPUT, "VOICE_AUDIO_LIMIT", "Realtime audio exceeds 30 minutes");
        byte[] copy = audio.clone(); audioBuffers.add(copy);
        buffered += copy.length; turnBytes += copy.length; totalBytes += copy.length;
        return enqueue(null, copy).minimalCompletionStage();
    }

    /** Commits a complete turn without sealing further input. / 提交完整轮次，不封闭后续输入。 */
    @Override public synchronized CompletionStage<Void> commit() throws ApiException {
        active(); return commitTurn().minimalCompletionStage();
    }

    private CompletableFuture<Void> commitTurn() throws ApiException {
        if (turnBytes < 4800)
            throw problem(ProblemCode.INVALID_INPUT, "VOICE_TURN_TOO_SHORT", "Each committed audio turn must contain at least 100 ms");
        if (committed >= MAX_TURNS || committed - finals.size() >= MAX_PENDING_TURNS)
            throw problem(ProblemCode.BUSY, "VOICE_TURN_BACKPRESSURE", "The bounded pending-turn queue is full");
        unacknowledged.addLast(committed++); turnBytes = 0;
        return enqueue("{\"type\":\"input_audio_buffer.commit\"}", null);
    }

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
                if (audio != null) { Arrays.fill(audio, (byte) 0); audioBuffers.remove(audio); buffered -= audio.length; }
                if (failure != null && !terminal)
                    fail(audioSent ? Outcome.UNKNOWN : Outcome.FAILED, ProblemCode.IO_FAILURE,
                        "VOICE_SEND_UNKNOWN", "The realtime send outcome could not be established");
                else completeIfReady();
            }
        });
        return next;
    }

    /** Seals input and awaits all committed final results. / 封闭输入并等待全部已提交最终结果。 */
    @Override public synchronized CompletionStage<OperationResult<VoiceResponse>> finish() throws ApiException {
        if (finishing || terminal) return completion();
        active();
        if (turnBytes > 0) commitTurn();
        if (committed == 0) throw problem(ProblemCode.INVALID_INPUT, "VOICE_EMPTY_INPUT", "No audio turn was committed");
        finishing = true; completeIfReady(); return completion();
    }

    private void completeIfReady() {
        if (!terminal && finishing && unacknowledged.isEmpty() && finals.size() == committed
            && outbound.isDone() && !outbound.isCompletedExceptionally())
            terminate(new OperationResult<>(Outcome.SUCCEEDED, Optional.of(confirmed()), Optional.empty()), true);
    }

    private void active() throws ApiException {
        if (terminal || finishing) throw problem(ProblemCode.UNAVAILABLE, "VOICE_SESSION_ENDED", "The session no longer accepts audio");
        if (!ready.isDone() || ready.isCompletedExceptionally())
            throw problem(ProblemCode.UNAVAILABLE, "VOICE_SESSION_NOT_READY", "Realtime transcription configuration is not ready");
        if (cancellation.requested()) { cancelTransport(); throw problem(ProblemCode.CANCELLED, "VOICE_CANCELLED", "Realtime input was cancelled"); }
    }

    private synchronized boolean audioWasSent() { return audioSent; }
    private synchronized VoiceResponse confirmed() {
        var parts = List.copyOf(finals.values());
        return new VoiceResponse(String.join("\n", parts.stream().map(VoiceEvent::text).toList()), modelId, parts);
    }
    private void fail(Outcome outcome, ProblemCode code, String event, String message) {
        synchronized (this) {
            terminate(VoiceProtocol.failure(outcome, code, event, message, modelId,
                finals.isEmpty() ? Optional.empty() : Optional.of(confirmed())), false);
        }
    }
    private void cancelTransport() {
        synchronized (this) {
            terminate(VoiceProtocol.failure(audioSent ? Outcome.UNKNOWN : Outcome.CANCELLED, ProblemCode.CANCELLED,
                "VOICE_CANCELLED", audioSent ? "Cancelled audio may have been processed; confirmed parts are retained"
                    : "Realtime input was cancelled before audio was sent", modelId,
                finals.isEmpty() ? Optional.empty() : Optional.of(confirmed())), false);
        }
    }

    private void terminate(OperationResult<VoiceResponse> result, boolean graceful) {
        if (terminal) return;
        terminal = true; finishing = true; frame.setLength(0); partials.clear();
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
                        try { connection.sendClose(WebSocket.NORMAL_CLOSURE, "finished").get(500, TimeUnit.MILLISECONDS); }
                        catch (Exception ignored) { }
                    }
                    connection.abort();
                }
                watchdog.shutdownNow();
                if (!watchdog.awaitTermination(2, TimeUnit.SECONDS)) throw new IOException("Voice deadline thread remains active");
                client.shutdownNow();
                if (!client.awaitTermination(Duration.ofSeconds(2))) throw new IOException("Voice socket client remains active");
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
        cleanup.setDaemon(true); cleanup.start();
    }

    /** Returns observation-only terminal completion. / 返回仅可观察的终态完成。 */
    @Override public CompletionStage<OperationResult<VoiceResponse>> completion() { return completion.minimalCompletionStage(); }
    /** Cancels input and waits for cleanup outside provider callbacks. / 取消输入，在提供方回调外等待清理。 */
    @Override public void cancel() throws ApiException { cancelTransport(); if (Thread.currentThread() != callbackThread) awaitCleanup(); }
    /** Cancels rather than committing an unfinished turn. / 取消而非提交未结束轮次。 */
    @Override public void close() throws ApiException {
        cancelTransport();
        if (Thread.currentThread() == callbackThread) return;
        try { awaitCleanup(); }
        catch (ApiException failure) {
            if (!failure.problem().eventCode().equals("VOICE_CLOSE_UNKNOWN")) throw failure;
            retryPhysicalClose();
        }
    }

    private void retryPhysicalClose() throws ApiException {
        boolean interrupted = Thread.interrupted();
        try {
            if (connecting != null && !connecting.isDone()) connecting.cancel(true);
            if (socket != null) socket.abort();
            watchdog.shutdownNow(); client.shutdownNow();
            if (!watchdog.awaitTermination(2, TimeUnit.SECONDS) || !client.awaitTermination(Duration.ofSeconds(2)))
                throw problem(ProblemCode.UNKNOWN, "VOICE_CLOSE_UNKNOWN", "Voice resources remain active after cleanup retry");
        } catch (InterruptedException failure) {
            interrupted = true;
            throw problem(ProblemCode.UNKNOWN, "VOICE_CLOSE_UNKNOWN", "Voice cleanup retry was interrupted");
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private OperationResult<VoiceResponse> awaitCleanup() throws ApiException {
        boolean interrupted = Thread.interrupted();
        try {
            var result = completion.get(6, TimeUnit.SECONDS);
            if (result.problem().map(Problem::eventCode).filter("VOICE_CLOSE_UNKNOWN"::equals).isPresent())
                throw new ApiException(result.problem().orElseThrow());
            return result;
        } catch (InterruptedException failure) {
            interrupted = true; throw problem(ProblemCode.UNKNOWN, "VOICE_CLOSE_UNKNOWN", "Voice cleanup was interrupted");
        } catch (ExecutionException | TimeoutException failure) {
            throw problem(ProblemCode.UNKNOWN, "VOICE_CLOSE_UNKNOWN", "Voice cleanup could not be confirmed");
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private static Throwable unwrap(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }
    private static ApiException problem(ProblemCode code, String event, String message) {
        return new ApiException(new Problem(code, event, message, Map.of(), Optional.empty()));
    }
}
