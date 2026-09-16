package gold.debug.windowstolinux.shared.ai.transport;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Rejects oversized responses before allocating the full body. / 在分配完整正文前拒绝超大响应。 */
final class LimitedHttpBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    static final int MAX_BYTES = 512 * 1024;
    private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private Flow.Subscription subscription;
    private int received;

    LimitedHttpBodySubscriber() {
        delegate.getBody().whenComplete((bytes, failure) -> {
            if (failure == null) body.complete(bytes); else body.completeExceptionally(failure);
        });
    }

    /** Returns the bounded completion. / 返回有界正文完成状态。 */
    @Override public CompletionStage<byte[]> getBody() { return body; }

    /** Connects upstream cancellation to the delegate. / 将上游取消连接到接收器。 */
    @Override public void onSubscribe(Flow.Subscription value) {
        subscription = value;
        delegate.onSubscribe(value);
    }

    /** Counts bytes before accepting each chunk. / 接受每批数据前累计字节数。 */
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

    /** Propagates transport failure. / 传递传输失败。 */
    @Override public void onError(Throwable failure) { delegate.onError(failure); }

    /** Completes a response within the limit. / 完成未超限的响应。 */
    @Override public void onComplete() { delegate.onComplete(); }
}
