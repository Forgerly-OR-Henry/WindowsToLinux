package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentInstallerEvidence;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryProgram;

/** Necessary delivery condition for one original executable input; it carries no review authority. */
final class DeploymentAgentInputCoverage {
    private static final ObjectMapper JSON = new ObjectMapper();

    static final int PAGE_CHARACTERS = 8000;

    static final int INLINE_PAYLOAD_BYTES = 14000;

    private static final int MAX_RANGES = 64;

    private static final String INPUT = "/completeExecutableInput";

    private static final List<String> STRUCTURED_BODIES = List.of(INPUT + "/sources/managedHelper",
        INPUT + "/sources/buildEntry", INPUT + "/segments/0/text", INPUT + "/segments/2/text", INPUT + "/segments/4/text");

    private record Range(int start, int endExclusive) {
    }

    private static final class Body {
        final String tool;

        final String argument;

        final String value;

        final String text;

        final String sha;

        List<Range> ranges = List.of();

        Body(String tool, String argument, String value, String text) {
            this.tool = tool;
            this.argument = argument;
            this.value = value;
            this.text = text;
            this.sha = sha(text);
        }

        boolean complete() {
            return text.isEmpty() || ranges.size() == 1 && ranges.getFirst().start() == 0
                && ranges.getFirst().endExclusive() == text.length();
        }

        void add(int start, int end) {
            if (start == end) return;
            var candidate = new ArrayList<>(ranges);
            candidate.add(new Range(start, end));
            candidate.sort(Comparator.comparingInt(Range::start));
            var merged = new ArrayList<Range>();
            for (var range : candidate) {
                if (merged.isEmpty() || merged.getLast().endExclusive() < range.start()) merged.add(range);
                else {
                    var prior = merged.removeLast();
                    merged.add(new Range(prior.start(), Math.max(prior.endExclusive(), range.endExclusive())));
                }
            }
            // Dropping excess observations can only leave a gap; it cannot manufacture delivery.
            if (merged.size() <= MAX_RANGES) ranges = List.copyOf(merged);
        }

        Map<String, Object> missing() {
            var gaps = new ArrayList<Range>();
            int cursor = 0;
            for (var range : ranges) {
                if (cursor < range.start()) gaps.add(new Range(cursor, range.start()));
                cursor = range.endExclusive();
            }
            if (cursor < text.length()) gaps.add(new Range(cursor, text.length()));
            return Map.of("tool", tool, "arguments", Map.of(argument, value), "sha256", sha,
                "total", text.length(), "ranges", gaps);
        }
    }

    private record Pending(Body body, int start, int end, String result) {
    }

    private final Body script;

    private final Body stdin;

    private final Map<String, Body> evidence = new LinkedHashMap<>();

    private final List<String> structured;

    private final List<Pending> pending = new ArrayList<>();
    private final List<Body> requiredEvidence = new ArrayList<>();
    private Body embeddedExecutable;

    DeploymentAgentInputCoverage(CommandRequest request, JsonNode facts) throws IOException {
        script = new Body("request", "part", "script", request.script());
        byte[] bytes = request.stdin();
        try {
            String kind = facts.path("inputKind").asText();
            if (kind.equals("OPAQUE_BOUND_DATA")) {
                stdin = null;
                structured = List.of();
                return;
            }
            if (kind.equals("EMPTY") || kind.isEmpty() && bytes.length == 0) {
                if (bytes.length != 0) throw new IOException("Empty input evidence contradicts the original request");
                stdin = null;
                structured = List.of();
                return;
            }
            if (kind.equals("TRACKED_TOOLCHAIN_REQUEST")) {
                var tracked = facts.path("agentTrackedToolchain");
                var recipeNode = tracked.path("recipeUtf8");
                boolean inline = tracked.path("invocationKind").asText().equals("EXISTING_INLINE");
                String sourcePointer = inline ? INPUT : "/agentToolchainInstalledInputBinding/controllerSource";
                var sourceNode = facts.at(sourcePointer);
                if (!recipeNode.isTextual() || !sourceNode.isTextual())
                    throw new IOException("Tracked controller requires its complete recipe and executable source");
                try {
                    var recipe = gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainTracked
                        .decode(recipeNode.textValue().getBytes(StandardCharsets.UTF_8));
                    String source = sourceNode.textValue();
                    if (!sha(source).equals(inline ? recipe.executedSourceSha256() : recipe.sourceSha256()))
                        throw new IOException("Tracked executable source differs from its exact program identity");
                    byte[] expected = inline ? (source + recipeNode.textValue()).getBytes(StandardCharsets.UTF_8) : recipe.bytes();
                    if (!Arrays.equals(bytes, expected)) throw new IOException("Tracked source and recipe do not reproduce original stdin");
                    stdin = new Body("request", "part", "stdin", StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
                    var executable = new Body("evidence", "pointer", sourcePointer, source);
                    var recipeBody = new Body("evidence", "pointer", "/agentTrackedToolchain/recipeUtf8", recipeNode.textValue());
                    evidence.put(sourcePointer, executable);
                    evidence.put(recipeBody.value, recipeBody);
                    requiredEvidence.add(executable);
                    if (inline) embeddedExecutable = executable;
                    structured = inline ? List.of(INPUT, recipeBody.value) : List.of(recipeBody.value);
                    return;
                } catch (IllegalArgumentException invalid) {
                    throw new IOException("Tracked original recipe is invalid", invalid);
                }
            }
            if (!kind.equals("EXECUTABLE_SCRIPT") && !kind.equals("SOURCE_PATCH"))
                throw new IOException("Original executable input classification is unavailable");
            String original = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            stdin = new Body("request", "part", "stdin", original);
            var represented = facts.at(INPUT);
            if (represented.isMissingNode()) {
                structured = List.of();
            } else if (represented.isTextual()) {
                if (!represented.textValue().equals(original))
                    throw new IOException("Executable evidence differs from the original request bytes");
                evidence.put(INPUT, stdin);
                structured = List.of(INPUT);
            } else {
                try {
                    if (represented.path("format").asText().equals(DeploymentAgentPublicationRecoveryProgram.INSTALL_EVIDENCE_FORMAT)) {
                        if (!facts.path("operation").asText().equals(DeploymentAgentPublicationRecoveryProgram.INSTALL))
                            throw new IllegalArgumentException("Publication installation evidence belongs to a different operation");
                        DeploymentAgentPublicationRecoveryProgram.verifyInstallationEvidence(represented, original);
                    } else DeploymentInstallerEvidence.verify(represented, original);
                } catch (IllegalArgumentException invalid) {
                    throw new IOException("Structured executable evidence does not reproduce the original request", invalid);
                }
                structured = represented.path("format").asText().equals(DeploymentAgentPublicationRecoveryProgram.INSTALL_EVIDENCE_FORMAT)
                    ? List.of(INPUT + "/sources/bash", INPUT + "/sources/loader", INPUT + "/derived/controller/segments/0/text",
                        INPUT + "/derived/controller/segments/2/text", INPUT + "/segments/0/text", INPUT + "/segments/2/text", INPUT + "/segments/4/text")
                    : STRUCTURED_BODIES;
                for (String leaf : structured) {
                    String pointer = leaf;
                    while (pointer.startsWith(INPUT)) {
                        var node = facts.at(pointer);
                        evidence.putIfAbsent(pointer, new Body("evidence", "pointer", pointer,
                            node.isTextual() ? node.textValue() : JSON.writeValueAsString(node)));
                        if (pointer.equals(INPUT)) break;
                        pointer = pointer.substring(0, pointer.lastIndexOf('/'));
                    }
                }
            }
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    Map<String, Object> inlineInput(int characterLimit, int encodedByteLimit) {
        if (characterLimit < 0 || characterLimit > PAGE_CHARACTERS || encodedByteLimit < 1 || encodedByteLimit > INLINE_PAYLOAD_BYTES)
            throw new IllegalArgumentException("Inline input must stay within one evidence page");
        var value = new LinkedHashMap<String, Object>();
        value.put("script", inline(script));
        if (stdin != null) value.put("stdin", inline(stdin));
        value.put("meaning", "Original request bodies. Descriptors and delivery coverage are not safety review or permission.");
        try {
            if (JSON.writeValueAsBytes(value).length > encodedByteLimit)
                throw new IllegalArgumentException("Complete inline descriptors exceed the evidence page budget");
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            throw new IllegalStateException("Original inline descriptors cannot be encoded", impossible);
        }
        int remaining = characterLimit;
        for (var body : stdin == null ? List.of(script) : List.of(script, stdin)) {
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(body.text))
                throw new IllegalArgumentException("Original inline body is not valid Unicode");
            if (body.text.length() > remaining) continue;
            @SuppressWarnings("unchecked") var entry = (Map<String, Object>) value.get(body.value);
            entry.put("text", body.text);
            try {
                if (JSON.writeValueAsBytes(value).length > encodedByteLimit) entry.remove("text");
                else remaining -= body.text.length();
            } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
                throw new IllegalStateException("Original inline input cannot be encoded", impossible);
            }
        }
        return value;
    }

    private static Map<String, Object> inline(Body body) {
        var value = new LinkedHashMap<String, Object>();
        value.put("tool", body.tool);
        value.put("arguments", Map.of(body.argument, body.value, "offset", 0, "limit", Math.max(1, Math.min(PAGE_CHARACTERS, body.text.length()))));
        value.put("total", body.text.length());
        value.put("sha256", body.sha);
        return value;
    }

    /** Register a successful local result, without claiming it has reached the approval model yet. */
    void returned(String tool, JsonNode arguments, Object value, String result) {
        Body body = null;
        if (tool.equals("request")) {
            String part = arguments.path("part").asText();
            body = part.equals("script") ? script : part.equals("stdin") ? stdin : null;
        } else if (tool.equals("evidence")) body = evidence.get(arguments.path("pointer").asText());
        if (body == null) return;
        JsonNode page = JSON.valueToTree(value);
        if (!page.path("text").isTextual() || !page.path("offset").isIntegralNumber()
            || !page.path("next").isIntegralNumber() || !page.path("total").isIntegralNumber()
            || !page.path("offset").canConvertToInt() || !page.path("next").canConvertToInt()) return;
        int start = page.path("offset").intValue(), end = page.path("next").intValue();
        String text = page.path("text").textValue();
        if (start < 0 || end < start || end > body.text.length() || end - start != text.length()
            || page.path("total").longValue() != body.text.length() || !page.path("sha256").asText().equals(body.sha)
            || !body.text.regionMatches(start, text, 0, text.length())) return;
        pending.add(new Pending(body, start, end, result));
    }

    /** Called only after a successful model call carrying this actual message payload. */
    void delivered(JsonNode input, Predicate<String> visibleResult) {
        inlineDelivered(script, input.at("/executableInput/script/text"));
        if (stdin != null) inlineDelivered(stdin, input.at("/executableInput/stdin/text"));
        for (var entry : evidence.entrySet()) {
            var body = entry.getValue();
            var node = input.path("currentRequest").at(entry.getKey());
            if (node.isMissingNode()) continue;
            String text = node.isTextual() ? node.textValue() : node.toString();
            if (body.text.equals(text)) body.add(0, body.text.length());
        }
        for (var page : pending)
            if (visibleResult.test(page.result())) page.body().add(page.start(), page.end());
        if (embeddedExecutable != null && stdin.complete())
            embeddedExecutable.add(0, embeddedExecutable.text.length());
        pending.clear();
    }

    private static void inlineDelivered(Body body, JsonNode value) {
        if (value.isTextual() && value.textValue().equals(body.text)) body.add(0, body.text.length());
    }

    boolean complete() {
        return requiredEvidence.stream().allMatch(Body::complete) && script.complete() && (stdin == null || stdin.complete() || !structured.isEmpty()
            && structured.stream().allMatch(this::represented));
    }

    private boolean represented(String pointer) {
        Body direct = evidence.get(pointer);
        if (direct != null && direct.complete()) return true;
        while (pointer.startsWith(INPUT)) {
            Body body = evidence.get(pointer);
            if (body != null && body.complete()) return true;
            if (pointer.equals(INPUT)) break;
            pointer = pointer.substring(0, pointer.lastIndexOf('/'));
        }
        return false;
    }

    Map<String, Object> missing() {
        var gaps = new ArrayList<Map<String, Object>>();
        if (!script.complete()) gaps.add(script.missing());
        requiredEvidence.stream().filter(body -> !body.complete()).forEach(body -> gaps.add(body.missing()));
        if (stdin != null && !stdin.complete() && (structured.isEmpty() || !structured.stream().allMatch(this::represented)))
            gaps.add(stdin.missing());
        return Map.of("cursorUnit", "UTF16_CODE_UNITS", "missingRanges", gaps,
            "equivalentEvidencePointers", structured, "authority", "NONE",
            "meaning", "Required original body ranges have not all been delivered. Choose available evidence tools or DENY. Successful delivery is necessary, never proof of reading, current understanding, safety or permission.");
    }

    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
