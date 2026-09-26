package gold.debug.windowstolinux.app.ai.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

class LimitedHttpBodySubscriberTest {
    @Test
    void acceptsExactLimitAndRejectsOneAdditionalByte() {
        var cancelled = new AtomicBoolean();
        var body = new LimitedHttpBodySubscriber(512 * 1024);
        body.onSubscribe(new Flow.Subscription() {
            public void request(long count) {
            }

            public void cancel() {
                cancelled.set(true);
            }
        });
        body.onNext(List.of(ByteBuffer.allocate(512 * 1024)));
        assertFalse(cancelled.get());
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1})));
        assertTrue(cancelled.get());
        assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
        body.onComplete();
        assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
    }

    @Test
    void countsAllBuffersAndPreservesAValidResponse() {
        var body = new LimitedHttpBodySubscriber(512 * 1024);
        body.onSubscribe(new Flow.Subscription() {
            public void request(long count) {
            }

            public void cancel() {
                fail("valid response was cancelled");
            }
        });
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2}), ByteBuffer.wrap(new byte[]{3})));
        body.onComplete();
        assertArrayEquals(new byte[]{1, 2, 3}, body.getBody().toCompletableFuture().join());
    }
}
