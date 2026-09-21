package gold.debug.windowstolinux.shared.ai.transport;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Rejects oversized responses before allocating the full body. / 在分配完整正文前拒绝超大响应。
 */
final class LimitedHttpBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    /**
     * MAX BYTES.
     * <p>最大字节。
     */
    static final int MAX_BYTES = 512 * 1024;
    /**
     * Delegate.
     * <p>被委派对象。
     */
    private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
    /**
     * Body.
     * <p>正文。
     */
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    /**
     * Subscription.
     * <p>订阅。
     */
    private Flow.Subscription subscription;
    /**
     * Bytes received so far against the output bound.
     * <p>当前已计入输出边界的接收字节数。
     */
    private int received;

    /**
     * Binds the supplied dependencies and state for limited http body subscriber.
     * <p>为LimitedHTTP正文Subscriber绑定传入的依赖及状态。
     */
    LimitedHttpBodySubscriber() {
        delegate.getBody().whenComplete((bytes, failure) -> {
            if (failure == null) body.complete(bytes); else body.completeExceptionally(failure);
        });
    }

    /**
     * Returns the bounded completion. / 返回有界正文完成状态。
     *
     * @return the bounded completion / 有界正文完成状态
     */
    @Override public CompletionStage<byte[]> getBody() { return body; }

    /**
     * Connects upstream cancellation to the delegate. / 将上游取消连接到接收器。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    @Override public void onSubscribe(Flow.Subscription value) {
        subscription = value;
        delegate.onSubscribe(value);
    }

    /**
     * Counts bytes before accepting each chunk. / 接受每批数据前累计字节数。
     *
     * @param buffers buffers / 缓冲区集合
     */
    @Override public void onNext(List<ByteBuffer> buffers) {
        if (body.isDone()) return;
        for (ByteBuffer buffer : buffers) {
            if (buffer.remaining() > MAX_BYTES - received) {
                subscription.cancel();
                delegate.onError(new IOException("AI response exceeded the 512 KiB transport limit"));
                return;
            }
            received += buffer.remaining();
        }
        delegate.onNext(buffers);
    }

    /**
     * Propagates transport failure. / 传递传输失败。
     *
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     */
    @Override public void onError(Throwable failure) { delegate.onError(failure); }

    /**
     * Completes a response within the limit. / 完成未超限的响应。
     */
    @Override public void onComplete() { delegate.onComplete(); }
}
