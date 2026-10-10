package gold.debug.windowstolinux.app.ai.model;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ModelMessageCodecTest {
    @Test
    void normalizedCacheUsagePreservesMissingCountsAndRejectsContradictions() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var reported = new java.util.LinkedHashMap<String, Object>(java.util.Map.of("prompt_tokens", 100L, "completion_tokens", 20L,
            "prompt_cache_hit_tokens", 80L, "prompt_cache_miss_tokens", 20L,
            "prompt_tokens_details", java.util.Map.of("cached_tokens", 80L), "completion_tokens_details", java.util.Map.of("reasoning_tokens", 12L)));
        var wire = new java.util.LinkedHashMap<String, Object>(java.util.Map.of("choices", java.util.List.of(java.util.Map.of(
            "finish_reason", "stop", "message", java.util.Map.of("content", "{}"))), "usage", reported));
        var usage = ModelMessageCodec.decode(json.writeValueAsBytes(wire), "any/model", true).usage();
        assertEquals(java.util.Optional.of(80L), usage.cachedInputTokens());
        assertEquals(java.util.Optional.of(20L), usage.cacheMissInputTokens());
        assertEquals(java.util.Optional.of(12L), usage.reasoningOutputTokens());
        reported.remove("prompt_cache_hit_tokens");
        reported.remove("prompt_cache_miss_tokens");
        usage = ModelMessageCodec.decode(json.writeValueAsBytes(wire), "any/model", true).usage();
        assertEquals(java.util.Optional.of(80L), usage.cachedInputTokens());
        assertTrue(usage.cacheMissInputTokens().isEmpty(), "Missing reported misses are unknown, not invented");
        for (var changes : java.util.List.of(java.util.Map.<String, Object>of("prompt_cache_hit_tokens", 81L),
            java.util.Map.<String, Object>of("prompt_cache_miss_tokens", 21L),
            java.util.Map.<String, Object>of("completion_tokens_details", java.util.Map.of("reasoning_tokens", 21L)),
            java.util.Map.<String, Object>of("prompt_cache_miss_tokens", -1L), java.util.Map.<String, Object>of("prompt_cache_miss_tokens", 0.5))) {
            var invalid = new java.util.LinkedHashMap<>(reported);
            invalid.putAll(changes);
            wire.put("usage", invalid);
            assertThrows(java.io.IOException.class, () -> ModelMessageCodec.decode(json.writeValueAsBytes(wire), "any/model", true));
        }
        wire.remove("usage");
        usage = ModelMessageCodec.decode(json.writeValueAsBytes(wire), "any/model", true).usage();
        assertTrue(usage.inputTokens().isEmpty());
        assertTrue(usage.cachedInputTokens().isEmpty());
    }

    @Test
    void malformedAndTruncatedResponsesRetainCacheCountsWithoutPrivateText() {
        byte[] bytes = ("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"private\",\"reasoning_content\":\"secret\"}}],"
            + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,\"prompt_cache_hit_tokens\":80,\"prompt_cache_miss_tokens\":20,"
            + "\"completion_tokens_details\":{\"reasoning_tokens\":12}}}").getBytes(StandardCharsets.UTF_8);
        var shape = ModelMessageCodec.responseShape(bytes);
        assertEquals("100", shape.get("inputTokens"));
        assertEquals("80", shape.get("cachedInputTokens"));
        assertEquals("20", shape.get("cacheMissInputTokens"));
        assertEquals("12", shape.get("reasoningOutputTokens"));
        assertFalse(shape.toString().contains("private"));
        assertFalse(shape.toString().contains("secret"));
    }

    @Test
    void concatenatedJsonDiagnosticsNeverSelectOrExposeAValue() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String content : java.util.List.of("{\"private-value\":1}\n\n{}", "{}\n[\"private-value\"]", "{}\n\"private-value\"")) {
            byte[] bytes = json.writeValueAsBytes(java.util.Map.of("choices", java.util.List.of(java.util.Map.of(
                "finish_reason", "stop", "message", java.util.Map.of("content", content)))));
            assertThrows(java.io.IOException.class, () -> ModelMessageCodec.decode(bytes, "generic/model", true));
            var shape = ModelMessageCodec.responseShape(bytes);
            assertEquals("INVALID", shape.get("jsonObject"));
            assertEquals("TRAILING_JSON_VALUE", shape.get("jsonFailureKind"));
            assertFalse(shape.toString().contains("private-value"));
        }
    }

    @Test
    void nonObjectDiagnosticsKeepTheSameStrictDecoderBoundary() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String content : java.util.List.of("[\"private-value\"]", "null", "\"private-value\"")) {
            byte[] bytes = json.writeValueAsBytes(java.util.Map.of("choices", java.util.List.of(java.util.Map.of(
                "finish_reason", "stop", "message", java.util.Map.of("content", content)))));
            assertThrows(java.io.IOException.class, () -> ModelMessageCodec.decode(bytes, "generic/model", true));
            var shape = ModelMessageCodec.responseShape(bytes);
            assertEquals("NON_OBJECT", shape.get("jsonFailureKind"));
            assertFalse(shape.toString().contains("private-value"));
        }
    }

    @Test
    void largeExecutableEvidenceAndEncodedArgumentReachTheWireWithoutTruncation() throws Exception {
        String encoded = java.util.Base64.getEncoder().encodeToString("complete binding\tvalue\n".getBytes(StandardCharsets.UTF_8));
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        String evidence = json.writeValueAsString(java.util.Map.of(
            "exactAction", "helper 'bind' '" + encoded + "'",
            "completeExecutableInput", "# start\n" + "printf 'bounded'\n".repeat(32000) + "# final line\n"));
        var request = new gold.debug.windowstolinux.appapi.ai.model.AiRequest(
            gold.debug.windowstolinux.appapi.ai.model.AiPurpose.APPROVAL,
            java.util.List.of(new gold.debug.windowstolinux.appapi.ai.model.AiMessage(
                    gold.debug.windowstolinux.appapi.ai.model.AiRole.SYSTEM, "Review all evidence", java.util.List.of()),
                new gold.debug.windowstolinux.appapi.ai.model.AiMessage(
                    gold.debug.windowstolinux.appapi.ai.model.AiRole.USER, evidence, java.util.List.of())),
            128, java.time.Duration.ofSeconds(20), true);
        var wire = json.readTree(ModelMessageCodec.encode("generic/model", request, 256));
        assertEquals(evidence, wire.at("/messages/1/content").asText());
        assertEquals("helper 'bind' '" + encoded + "'", json.readTree(wire.at("/messages/1/content").asText()).path("exactAction").asText());
        var tooLarge = new gold.debug.windowstolinux.appapi.ai.model.AiRequest(request.purpose(),
            java.util.List.of(new gold.debug.windowstolinux.appapi.ai.model.AiMessage(
                gold.debug.windowstolinux.appapi.ai.model.AiRole.USER, "x".repeat(1048577), java.util.List.of())),
            request.maxOutputTokens(), request.timeout(), true);
        assertThrows(IllegalArgumentException.class, () -> ModelMessageCodec.encode("generic/model", tooLarge, 256));
    }

    @Test
    void optionalReasoningEffortPreservesDefaultWireAndOriginalRequest() throws Exception {
        var request = new gold.debug.windowstolinux.appapi.ai.model.AiRequest(
            gold.debug.windowstolinux.appapi.ai.model.AiPurpose.APPROVAL,
            java.util.List.of(new gold.debug.windowstolinux.appapi.ai.model.AiMessage(
                gold.debug.windowstolinux.appapi.ai.model.AiRole.USER, "完整审批依据", java.util.List.of())),
            128, java.time.Duration.ofSeconds(20), true);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var original = json.readTree(ModelMessageCodec.encode("generic/model", request, 256));
        assertFalse(original.has("reasoning_effort"));
        for (var effort : ModelReasoningLevel.values()) {
            var encoded = json.readTree(ModelMessageCodec.encode("generic/model", request, 256, effort));
            if (effort == ModelReasoningLevel.DEFAULT) assertEquals(original, encoded);
            else {
                assertEquals(effort.protocolValue(), encoded.path("reasoning_effort").textValue());
                ((com.fasterxml.jackson.databind.node.ObjectNode) encoded).remove("reasoning_effort");
                assertEquals(original, encoded);
            }
        }
        assertEquals(64, json.readTree(ModelMessageCodec.encode("generic/model", request, 64, ModelReasoningLevel.LOW)).path("max_tokens").intValue());
        assertThrows(IllegalArgumentException.class,
            () -> ModelMessageCodec.encode("generic/model", request, 0, ModelReasoningLevel.LOW));
    }

    @Test
    void invalidJsonDiagnosticsDistinguishTruncationWithoutExposingText() {
        byte[] bytes = ("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"private body\","
            + "\"reasoning_content\":\"private reasoning\"}}],\"usage\":{\"completion_tokens\":8192}}").getBytes(StandardCharsets.UTF_8);
        assertThrows(java.io.IOException.class, () -> ModelMessageCodec.decode(bytes, "model", true));
        var shape = ModelMessageCodec.responseShape(bytes);
        assertEquals("length", shape.get("finishReason"));
        assertEquals("8192", shape.get("outputTokens"));
        assertEquals("12", shape.get("contentCharacters"));
        assertEquals("17", shape.get("auxiliaryTextCharacters"));
        assertEquals("INVALID", shape.get("jsonObject"));
        assertEquals("OTHER", shape.get("contentForm"));
        assertFalse(shape.toString().contains("private"));
    }

    @Test
    void invalidEnvelopeAndUnknownFinishReasonNeverExposeProviderValues() {
        assertEquals(java.util.Map.of("responseEnvelope", "INVALID"), ModelMessageCodec.responseShape("not-json".getBytes(StandardCharsets.UTF_8)));
        var shape = ModelMessageCodec.responseShape("{\"choices\":[{\"finish_reason\":\"private-value\"}]}".getBytes(StandardCharsets.UTF_8));
        assertEquals("OTHER", shape.get("finishReason"));
        assertFalse(shape.toString().contains("private-value"));
    }

    @Test
    void auxiliaryTextDiagnosticsAreIndependentOfExtensionNamesAndNeverExposeTheirValues() {
        for (String extension : java.util.List.of("analysis", "reasoning_content", "provider_private_text")) {
            var shape = ModelMessageCodec.responseShape(("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"{}\",\"" + extension + "\":\"private\",\"second_extension\":\"text\","
                + "\"number\":42,\"nested\":{\"text\":\"hidden\"}}}]}").getBytes(StandardCharsets.UTF_8));
            assertEquals("11", shape.get("auxiliaryTextCharacters"));
            assertFalse(shape.toString().contains("private"));
            assertFalse(shape.containsKey(extension));
            assertFalse(shape.toString().contains("hidden"));
        }
        var shape = ModelMessageCodec.responseShape("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}]}".getBytes(StandardCharsets.UTF_8));
        assertEquals("0", shape.get("auxiliaryTextCharacters"));
    }

    @Test
    void jsonFormatDiagnosticsDistinguishFencesAndSyntaxWithoutReturningContent() {
        var fenced = ModelMessageCodec.responseShape("{\"choices\":[{\"message\":{\"content\":\"```json\\n{}\\n```\"}}]}".getBytes(StandardCharsets.UTF_8));
        assertEquals("CODE_FENCE", fenced.get("contentForm"));
        assertEquals("INVALID", fenced.get("jsonObject"));
        var syntax = ModelMessageCodec.responseShape("{\"choices\":[{\"message\":{\"content\":\"{private-value}\"}}]}".getBytes(StandardCharsets.UTF_8));
        assertEquals("OBJECT_PREFIX", syntax.get("contentForm"));
        assertEquals("INVALID", syntax.get("jsonObject"));
        assertEquals("MALFORMED_JSON", syntax.get("jsonFailureKind"));
        assertNotNull(syntax.get("jsonErrorColumn"));
        assertFalse(syntax.toString().contains("private-value"));
    }
}
