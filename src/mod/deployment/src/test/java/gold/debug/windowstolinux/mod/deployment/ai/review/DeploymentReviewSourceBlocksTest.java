package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DeploymentReviewSourceBlocksTest {
    @Test
    void publishedHeaderKeepsEveryByteWithBoundedReadableLineEdits() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("test/multi-language/success-log-analyzer/native/vendor/nlohmann/json.hpp")))
            root = root.getParent();
        assertTrue(root != null);
        String source = Files.readString(root.resolve("test/multi-language/success-log-analyzer/native/vendor/nlohmann/json.hpp"));
        var encoded = DeploymentReviewSourceBlocks.encode(source).orElseThrow();
        assertTrue(encoded.edits() > 0);
        assertEquals(source, DeploymentReviewSourceBlocks.expand(encoded.text(), source.length()));
        assertEquals(source, DeploymentSourceFrameDecoder.expand(encoded.text()));
        System.out.printf("R101_HEADER_RESOURCE_ONLY originalCharacters=%d displayCharacters=%d quotedBytes=%d references=%d edits=%d nativeFitNotMeasured=true%n",
            source.length(), encoded.text().length(), new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(encoded.text()).length,
            encoded.references(), encoded.edits());
    }

    @Test
    void lineEditsPreserveUnicodeCrLfAndRejectUnavailableOrMalformedReferences() throws Exception {
        String first = "    very_long_function_name(\"π💡原文\", argument_one, argument_two);\r\n";
        String second = "    very_long_function_name(\"π🔒原文\", argument_one, argument_two);\r\n";
        String original = first + second + "separator\n" + first + second + "partial 💡";
        var encoded = DeploymentReviewSourceBlocks.encode(original).orElseThrow();
        assertTrue(encoded.edits() > 0);
        assertEquals(original, DeploymentReviewSourceBlocks.expand(encoded.text(), original.length()));
        assertEquals(original, DeploymentSourceFrameDecoder.expand(encoded.text()));
        String editedLast = first + second + "separator\n" + first + second.stripTrailing();
        var finalEdit = DeploymentReviewSourceBlocks.encode(editedLast).orElseThrow();
        assertTrue(finalEdit.text().substring(finalEdit.text().lastIndexOf('\n', finalEdit.text().length() - 2) + 1).startsWith("@WTL_EDIT "));
        assertEquals(editedLast, DeploymentReviewSourceBlocks.expand(finalEdit.text(), editedLast.length()));
        assertEquals(editedLast, DeploymentSourceFrameDecoder.expand(finalEdit.text()));
        assertThrows(IOException.class, () -> DeploymentReviewSourceBlocks.expand("@WTL_EDIT 1 0 0 \"x\\n\"\n", 2));
        assertThrows(IOException.class, () -> DeploymentReviewSourceBlocks.expand("x\n@WTL_EDIT 1 999999 0 \"\\n\"\n", 4));
        assertThrows(IOException.class, () -> DeploymentReviewSourceBlocks.expand("x\n@WTL_EDIT 1 1 0 \"x\" \"y\"\n", 4));
        assertTrue(DeploymentReviewSourceBlocks.encode(original + "@WTL_EDIT 1 1 1 \"x\"\n").isEmpty());
    }

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
        String small = "x".repeat(61) + "\n";
        String escaped = "\"".repeat(34) + "\n";
        assertTrue(escaped.getBytes(StandardCharsets.UTF_8).length < 64);
        assertTrue(DeploymentReviewSourceBlocks.encode(small.repeat(2)).isEmpty());
        assertEquals(1, DeploymentReviewSourceBlocks.encode(escaped.repeat(2)).orElseThrow().references());
        assertTrue(DeploymentReviewSourceBlocks.encode(escaped.repeat(2) + "@WTL_BLOCK 1 1\n").isEmpty());
        assertTrue(DeploymentReviewSourceBlocks.encode(escaped.repeat(2) + "  // @WTL_COPY\n").isEmpty());
        assertTrue(DeploymentReviewSourceBlocks.encode(escaped.repeat(2) + "// @>12 value\n").isEmpty());
        assertTrue(DeploymentReviewSourceBlocks.encode(escaped.repeat(2) + "\ud800").isEmpty());
    }

    @Test
    void restoresLeadingSpacesInAnchorsAndTheirCopies() throws Exception {
        String repeated = " ".repeat(24) + "function(\"verbatim 世界\");\r\n" + " ".repeat(17) + "\treturn 1;\n";
        String original = repeated + " ".repeat(12) + "\nseparator\n" + repeated + " ".repeat(999) + "tail";
        var encoded = DeploymentReviewSourceBlocks.encode(original).orElseThrow();

        assertTrue(encoded.text().contains("@>24 function"));
        assertTrue(encoded.text().contains("@>17 \treturn"));
        assertTrue(encoded.text().contains("@>999 tail"));
        assertEquals(original, DeploymentReviewSourceBlocks.expand(encoded.text(), original.length()));

        String invalid = encoded.text().replace("@>24 ", "@>02 ");
        assertThrows(IOException.class, () -> DeploymentReviewSourceBlocks.expand(invalid, original.length()));
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
            "@WTL_BLOCK 1 1\na\n" + "@WTL_COPY 1 1\n".repeat(2049), 4100));
    }
}
