package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class DeploymentAssistedAcceptanceFailureTest {
    @Test
    void businessFailureKeepsIdentityAndStackWhenCleanupAlsoFails() {
        for (Throwable business : new Throwable[] {new IOException("business"), new AssertionError("business")}) {
            Throwable cleanup = new IllegalStateException("cleanup UNKNOWN");
            var businessStack = business.getStackTrace();
            var cleanupStack = cleanup.getStackTrace();
            Throwable observed = assertThrows(business.getClass(), () -> {
                try {
                    throw business;
                } finally {
                    DeploymentStaticLiveAcceptanceTest.finishAssistedAcceptance(business, cleanup);
                }
            });
            assertSame(business, observed);
            assertArrayEquals(businessStack, observed.getStackTrace());
            assertArrayEquals(new Throwable[] {cleanup}, observed.getSuppressed());
            assertArrayEquals(cleanupStack, cleanup.getStackTrace());
        }
    }

    @Test
    void cleanupFailureKeepsIdentityWhenBusinessSucceeded() {
        for (Throwable cleanup : new Throwable[] {new IOException("cleanup"), new AssertionError("cleanup")}) {
            var stack = cleanup.getStackTrace();
            Throwable observed = assertThrows(cleanup.getClass(), () ->
                DeploymentStaticLiveAcceptanceTest.finishAssistedAcceptance(null, cleanup));
            assertSame(cleanup, observed);
            assertArrayEquals(stack, observed.getStackTrace());
            assertEquals(0, observed.getSuppressed().length);
        }
    }

    @Test
    void absentCleanupFailureDoesNotCreateFailureOrSuppression() {
        assertDoesNotThrow(() -> DeploymentStaticLiveAcceptanceTest.finishAssistedAcceptance(null, (Exception) null));
        var business = new IOException("business");
        Throwable observed = assertThrows(IOException.class, () -> {
            try {
                throw business;
            } finally {
                DeploymentStaticLiveAcceptanceTest.finishAssistedAcceptance(business, (Exception) null);
            }
        });
        assertSame(business, observed);
        assertEquals(0, observed.getSuppressed().length);
    }

    @Test
    void sameFailureDoesNotSelfSuppressOrLoseExistingSuppression() {
        var business = new AssertionError("same business and cleanup object");
        var earlier = new IOException("earlier diagnostic failure");
        business.addSuppressed(earlier);
        var stack = business.getStackTrace();
        Throwable observed = assertThrows(AssertionError.class, () -> {
            try {
                throw business;
            } finally {
                DeploymentStaticLiveAcceptanceTest.finishAssistedAcceptance(business, business);
            }
        });
        assertSame(business, observed);
        assertArrayEquals(stack, observed.getStackTrace());
        assertArrayEquals(new Throwable[] {earlier}, observed.getSuppressed());
    }
}
