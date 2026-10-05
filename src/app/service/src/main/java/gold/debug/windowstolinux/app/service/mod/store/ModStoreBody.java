package gold.debug.windowstolinux.app.service.mod.store;

import java.io.*;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

import gold.debug.windowstolinux.app.windows.file.WindowsFileIdentity;

/** Bounded streaming body whose future completes only after EOF and file closure. / 仅在 EOF 及文件关闭后完成的有界流式 body。 */
final class ModStoreBody implements HttpResponse.BodySubscriber<ModStoreBody.Result>, AutoCloseable {
    /** Maximum bytes this actual response may receive. / 此实际响应可接收的字节上限。 */
    private final long maximum;

    /** Exact package bytes, or zero for a bounded catalog. / 精确包字节，目录则为零。 */
    private final long expected;

    /** Actual byte observer. / 实际字节观察者。 */
    private final Consumer<ModStoreGateway.Progress> progress;

    /** Exclusively created ordinary temporary path, absent for catalog memory. / 排他创建的普通临时路径，目录内存则无。 */
    private final Path file;

    /** Exact created native file identity. / 精确创建的原生文件身份。 */
    private final String identity;

    /** Bounded catalog output. / 有界目录输出。 */
    private final ByteArrayOutputStream memory;

    /** Actual package output, never exposed as a formal original. / 实际包输出，不作为正式原包暴露。 */
    private final FileChannel output;

    /** Exact received bytes digest. / 精确收到字节摘要。 */
    private final MessageDigest digest;

    /** Completion after the actual output closes. / 实际输出关闭后的完成。 */
    private final CompletableFuture<Result> body = new CompletableFuture<>();

    /** Original subscription, cancelled at the same owner boundary. / 同一所有者边界取消的原订阅。 */
    private Flow.Subscription subscription;

    /** Actual accepted byte count. / 实际接受字节数。 */
    private long count;

    /** Whether actual EOF or cancellation closed this owner. / 实际 EOF 或取消是否已关闭此所有者。 */
    private boolean closed;

    /** Captures bounds and exclusively creates the requested temporary file. / 捕获界限并排他创建请求临时文件。
     * @param maximum exact response cap / 精确响应上限
     * @param expected package length or zero / 包长度或零
     * @param file new temporary file or null / 新临时文件或空
     * @param progress actual observer / 实际观察者
     * @throws IOException if output cannot be opened / 无法打开输出时 */
    ModStoreBody(long maximum, long expected, Path file, Consumer<ModStoreGateway.Progress> progress) throws IOException {
        this.maximum = maximum;
        this.expected = expected;
        this.file = file;
        this.progress = progress;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        memory = file == null ? new ByteArrayOutputStream() : null;
        output = file == null ? null : FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        String created = null;
        try {
            if (file != null) created = WindowsFileIdentity.read(file);
        } catch (IOException failure) {
            output.close();
            throw failure;
        }
        identity = created;
    }

    /** Complete body facts, available only after close. / 仅关闭后可用的完整 body 事实。
     * @param bytes bounded catalog bytes or empty for a file / 有界目录字节或文件时为空
     * @param count exact received bytes / 精确收到字节
     * @param sha256 exact received digest / 精确收到摘要 */
    record Result(byte[] bytes, long count, String sha256) {
    }

    /** Exposes only EOF completion. / 仅暴露 EOF 完成。
     * @return actual completion / 实际完成 */
    public CompletionStage<Result> getBody() {
        return body;
    }

    /** Accepts one original subscription. / 接受一个原订阅。
     * @param incoming actual subscription / 实际订阅 */
    public synchronized void onSubscribe(Flow.Subscription incoming) {
        if (closed || subscription != null) incoming.cancel();
        else {
            subscription = incoming;
            incoming.request(1);
        }
    }

    /** Writes only bounded original chunks under exclusive output ownership. / 在排他输出归属下仅写有界原块。
     * @param chunks actual HTTP buffers / 实际 HTTP 缓冲 */
    public synchronized void onNext(List<ByteBuffer> chunks) {
        if (closed) return;
        try {
            for (var chunk : chunks) {
                int length = chunk.remaining();
                if (length > maximum - count) throw new IOException("Response exceeds its declared bound");
                digest.update(chunk.duplicate());
                if (output != null) while (chunk.hasRemaining()) output.write(chunk);
                else {
                    byte[] copy = new byte[length];
                    chunk.get(copy);
                    memory.write(copy);
                }
                count += length;
            }
            progress.accept(new ModStoreGateway.Progress(count, expected));
            subscription.request(1);
        } catch (Exception failure) {
            onError(failure);
        }
    }

    /** Closes actual output before publishing failure. / 发布失败前关闭实际输出。
     * @param failure original protocol failure / 原协议失败 */
    public synchronized void onError(Throwable failure) {
        if (closed) return;
        closed = true;
        if (subscription != null) subscription.cancel();
        try {
            if (output != null) output.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
        body.completeExceptionally(failure);
    }

    /** Requires exact package size at EOF before publishing complete facts. / 发布完整事实前在 EOF 要求精确包大小。 */
    public synchronized void onComplete() {
        if (closed) return;
        if (expected != 0 && count != expected) {
            onError(new IOException("Package length differs from catalog"));
            return;
        }
        try {
            if (output != null) {
                output.force(true);
                output.close();
            }
            closed = true;
            body.complete(new Result(memory == null ? new byte[0] : memory.toByteArray(), count, HexFormat.of().formatHex(digest.digest())));
        } catch (IOException failure) {
            onError(failure);
        }
    }

    /** Cancels original subscription and closes output, preserving cleanup failures. / 取消原订阅并关闭输出，保留清理失败。
     * @throws IOException if actual output did not close / 实际输出未关闭时 */
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            if (subscription != null) subscription.cancel();
            try {
                if (output != null) output.close();
            } finally {
                body.completeExceptionally(new CancellationException("Store body closed"));
            }
        }
    }

    /** Deletes only this exact created temporary file. / 仅删除此精确创建的临时文件。
     * @throws IOException if original identity changed / 原身份变化时 */
    synchronized void discard() throws IOException {
        close();
        if (file != null && Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!identity.equals(WindowsFileIdentity.read(file)))
                throw new IOException("Temporary download identity changed");
            Files.delete(file);
        }
    }
}
