package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DeploymentReviewSourceBlocksTest {
    @Test
    void preservesExactMixedLineEndingsAndPartialFinalLine() throws Exception {
        String repeated = "  π \" \\\t text ".repeat(12) + "\r\n" + "  世界 \" \\\t ".repeat(11) + "\r";
        String original = "head\n" + repeated + "between\n" + repeated + "last 💡";
        var encoded = DeploymentReviewSourceBlocks.encode(original).orElseThrow();

        assertEquals(7, encoded.originalLines());
        assertEquals(1, encoded.references());
        assertEquals("head\n@WTL_BLOCK 2 2\n" + repeated + "between\n@WTL_COPY 2 2\nlast 💡", encoded.text());
        assertEquals(original, DeploymentReviewSourceBlocks.expand(encoded.text(), original.length()));
    }

    @Test
    void usesJsonEscapedByteThresholdAndSkipsUnsafeSource() throws Exception {
        String small = "x".repeat(188) + "\n";
        String escaped = "\"".repeat(97) + "\n";
        assertTrue(escaped.getBytes(StandardCharsets.UTF_8).length < 192);
        assertTrue(DeploymentReviewSourceBlocks.encode(small.repeat(2)).isEmpty());
        assertEquals(1, DeploymentReviewSourceBlocks.encode(escaped.repeat(2)).orElseThrow().references());
        assertTrue(DeploymentReviewSourceBlocks.encode(escaped.repeat(2) + "@WTL_BLOCK 1 1\n").isEmpty());
        assertTrue(DeploymentReviewSourceBlocks.encode(escaped.repeat(2) + "  // @WTL_COPY\n").isEmpty());
        assertTrue(DeploymentReviewSourceBlocks.encode(escaped.repeat(2) + "\ud800").isEmpty());
    }

    @Test
    void rejectsMissingForwardNestedAndDuplicateAnchors() {
        assertThrows(IOException.class, () -> DeploymentReviewSourceBlocks.expand("@WTL_COPY 1 1\n", 2));
        assertThrows(IOException.class, () -> DeploymentReviewSourceBlocks.expand(
            "@WTL_COPY 2 1\n@WTL_BLOCK 2 1\na\n", 4));
        assertThrows(IOException.class, () -> DeploymentReviewSourceBlocks.expand(
            "@WTL_BLOCK 1 2\na\n@WTL_BLOCK 2 1\nb\n@WTL_COPY 1 2\n@WTL_COPY 2 1\n", 10));
        assertThrows(IOException.class, () -> DeploymentReviewSourceBlocks.expand(
            "@WTL_BLOCK 1 1\na\n@WTL_BLOCK 2 2\n@WTL_COPY 1 1\nb\n@WTL_COPY 2 2\n", 10));
        assertThrows(IOException.class, () -> DeploymentReviewSourceBlocks.expand(
            "@WTL_BLOCK 1 1\na\n@WTL_BLOCK 1 1\n@WTL_COPY 1 1\n", 4));
    }

    @Test
    void rejectsUnusedMalformedAndOversizedRepresentations() {
        String valid = "@WTL_BLOCK 1 1\na\n@WTL_COPY 1 1\n";
        assertThrows(IOException.class, () -> DeploymentReviewSourceBlocks.expand(
            "@WTL_BLOCK 1 1\na\n@WTL_BLOCK 2 1\nb\n@WTL_COPY 2 1\n", 6));
        assertThrows(IOException.class, () -> DeploymentReviewSourceBlocks.expand(
            "@WTL_BLOCK 1 1\na\n @WTL_COPY 1 1\n", 4));
        assertThrows(IOException.class, () -> DeploymentReviewSourceBlocks.expand(
            "@WTL_BLOCK 1 1\r\na\n@WTL_COPY 1 1\n", 4));
        assertThrows(IOException.class, () -> DeploymentReviewSourceBlocks.expand(
            "line\r@WTL_BLOCK 2 1\n\n@WTL_COPY 2 1\n", 7));
        assertThrows(IOException.class, () -> DeploymentReviewSourceBlocks.expand(valid, 3));
        assertThrows(IOException.class, () -> DeploymentReviewSourceBlocks.expand(valid, 5));
        assertThrows(IOException.class, () -> DeploymentReviewSourceBlocks.expand(valid, 1024 * 1024 + 1));
        assertThrows(IOException.class, () -> DeploymentReviewSourceBlocks.expand(
            "@WTL_BLOCK 1 1\na\n" + "@WTL_COPY 1 1\n".repeat(513), 1028));
    }
}
