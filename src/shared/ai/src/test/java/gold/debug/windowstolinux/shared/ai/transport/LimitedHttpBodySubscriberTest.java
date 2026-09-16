package gold.debug.windowstolinux.shared.ai.transport;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class LimitedHttpBodySubscriberTest {
    @Test void acceptsExactLimitAndRejectsOneAdditionalByte() {
        var cancelled = new AtomicBoolean();
        var body = new LimitedHttpBodySubscriber();
        body.onSubscribe(new Flow.Subscription() {
            public void request(long count) { }
            public void cancel() { cancelled.set(true); }
        });
        body.onNext(List.of(ByteBuffer.allocate(LimitedHttpBodySubscriber.MAX_BYTES)));
        assertFalse(cancelled.get());
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1})));
        assertTrue(cancelled.get());
        assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
        body.onComplete();
        assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
    }

    @Test void countsAllBuffersAndPreservesAValidResponse() {
        var body = new LimitedHttpBodySubscriber();
        body.onSubscribe(new Flow.Subscription() {
            public void request(long count) { }
            public void cancel() { fail("valid response was cancelled"); }
        });
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2}), ByteBuffer.wrap(new byte[]{3})));
        body.onComplete();
        assertArrayEquals(new byte[]{1, 2, 3}, body.getBody().toCompletableFuture().join());
    }
}
