package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.util.*;

/** Decodes only the public non-secret workload protocol for independent review; grants no authority. */
final class DeploymentWorkloadReviewEvidence {
    private final List<String> fields;

    private int index;

    private DeploymentWorkloadReviewEvidence(List<String> fields) {
        this.fields = fields;
    }

    static Map<String, Object> decode(String encoded, boolean healthOnly) throws CharacterCodingException {
        if (!encoded.matches("[A-Za-z0-9+/]{1,87384}={0,2}"))
            throw new IllegalArgumentException("Invalid workload encoding");
        byte[] bytes = Base64.getDecoder().decode(encoded);
        try {
            if (bytes.length == 0 || bytes.length > 65536 || bytes[bytes.length - 1] != 0
                || !Base64.getEncoder().encodeToString(bytes).equals(encoded))
                throw new IllegalArgumentException("Noncanonical workload frame");
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var parsed = new DeploymentWorkloadReviewEvidence(List.of(text.substring(0, text.length() - 1).split("\\x00", -1)));
            var result = new LinkedHashMap<String, Object>();
            String version = parsed.field();
            result.put("format", version);
            if (healthOnly) {
                if (!version.equals("health-v1")) throw new IllegalArgumentException("Unsupported health frame");
            } else {
                if (!Set.of("application-v1", "application-v2").contains(version))
                    throw new IllegalArgumentException("Unsupported workload frame");
                result.put("mode", parsed.field());
                result.put("reviewedFlagLiteral", parsed.field());
                result.put("workingDirectory", parsed.field());
                result.put("buildDirectory", parsed.field());
                result.put("command", parsed.command());
                result.put("verification", parsed.optionalCommand());
                result.put("expectedOutput", parsed.field());
                result.put("client", parsed.optionalCommand());
                result.put("endpoints", parsed.entries("id", "protocol", "bindAddress", "hostPort", "targetPort", "exposure", "accessUrl"));
                result.put("inputs", parsed.entries("id", "hostPath", "accessPath"));
                result.put("companions", parsed.entries("id", "sourcePath", "projectType", "artifactPath", "environment"));
                var workers = new ArrayList<Map<String, Object>>();
                if (version.equals("application-v2"))
                    for (int count = parsed.number(0, 8); count > 0; count--)
                        workers.add(Map.of("id", parsed.field(), "command", parsed.command()));
                result.put("workers", List.copyOf(workers));
            }
            result.put("health", parsed.health());
            if (parsed.index != parsed.fields.size()) throw new IllegalArgumentException("Trailing workload fields");
            result.put("payloadBytes", bytes.length);
            try {
                result.put("payloadSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
            result.put("literalValidation", "CANONICAL_BASE64_STRICT_UTF8_COMPLETE_NUL_FIELD_STRUCTURE_ONLY");
            result.put("meaning", "Complete decoded values of the exact non-secret workload argument; all entrypoints/argv, optional verification/client/workers, companions, endpoints and health are shown. This is typed data, not an omitted shell body; still review all executable arguments and reachable effects. reviewedFlagLiteral is input, not a safety verdict. Shape/byte consistency do not prove current installation, source trust, ownership or permission. The full surrounding command and its remaining arguments require independent review; protected configuration/secret payloads are not decoded.");
            return Collections.unmodifiableMap(result);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private String field() {
        if (index >= fields.size()) throw new IllegalArgumentException("Truncated workload fields");
        String value = fields.get(index++);
        if (value.length() > 8192 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            throw new IllegalArgumentException("Invalid workload field");
        return value;
    }

    private int number(int minimum, int maximum) {
        String value = field();
        if (!value.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid workload count");
        int count = Integer.parseInt(value);
        if (count < minimum || count > maximum) throw new IllegalArgumentException("Workload count outside bounds");
        return count;
    }

    private Map<String, Object> command() {
        String entrypoint = field();
        var args = new ArrayList<String>();
        for (int count = number(0, 64); count > 0; count--) {
            String value = field();
            if (value.length() > 4096) throw new IllegalArgumentException("Workload argument outside bounds");
            args.add(value);
        }
        return Map.of("entrypoint", entrypoint, "arguments", List.copyOf(args));
    }

    private Map<String, Object> optionalCommand() {
        return number(0, 1) == 0 ? Map.of("present", false) : Map.of("present", true, "command", command());
    }

    private List<Map<String, Object>> entries(String... names) {
        var entries = new ArrayList<Map<String, Object>>();
        for (int count = number(0, 32); count > 0; count--) {
            var entry = new LinkedHashMap<String, Object>();
            for (String name : names) entry.put(name, field());
            entries.add(Map.copyOf(entry));
        }
        return List.copyOf(entries);
    }

    private Map<String, Object> health() {
        var result = new LinkedHashMap<String, Object>();
        String kind = field();
        result.put("kind", kind);
        result.put("timeoutSeconds", number(1, 300));
        switch (kind) {
            case "HTTP" -> {
                result.put("endpoint", field());
                result.put("expectedStatus", number(200, 399));
            }
            case "TCP" -> {
                result.put("port", number(1, 65535));
                result.put("stabilitySeconds", number(1, 300));
            }
            case "PROCESS" -> result.put("stabilitySeconds", number(1, (int) result.get("timeoutSeconds")));
            case "COMMAND" -> {
                result.put("command", command());
                result.put("expectedOutput", field());
            }
            case "UDP" -> {
                result.put("port", number(1, 65535));
                result.put("requestHex", field());
                result.put("responseHex", field());
                result.put("probe", optionalCommand());
            }
            default -> throw new IllegalArgumentException("Unsupported workload health variant");
        }
        return Map.copyOf(result);
    }
}
