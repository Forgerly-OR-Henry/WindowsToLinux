package gold.debug.windowstolinux.app.ai.http;

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
        assertEquals(512 * 1024, body.acceptedBytes());
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1})));
        assertTrue(cancelled.get());
        assertEquals(512 * 1024, body.acceptedBytes(), "A rejected batch must not count as accepted body bytes");
        assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());

        var rejectedBatch = new LimitedHttpBodySubscriber(3);
        rejectedBatch.onSubscribe(new Flow.Subscription() {
            public void request(long count) {
            }

            public void cancel() {
            }
        });
        rejectedBatch.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2}), ByteBuffer.wrap(new byte[]{3, 4})));
        assertEquals(0, rejectedBatch.acceptedBytes(), "No part of an oversized batch was accepted");
        assertTrue(rejectedBatch.getBody().toCompletableFuture().isCompletedExceptionally());
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
        assertEquals(3, body.acceptedBytes());
        body.onComplete();
        assertArrayEquals(new byte[]{1, 2, 3}, body.getBody().toCompletableFuture().join());
    }
}
