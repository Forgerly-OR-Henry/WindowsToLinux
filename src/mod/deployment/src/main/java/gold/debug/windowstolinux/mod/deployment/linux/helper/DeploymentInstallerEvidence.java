package gold.debug.windowstolinux.mod.deployment.linux.helper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Lossless ordered installer evidence. No executable bytes are omitted or executed here. */
public final class DeploymentInstallerEvidence {
    private static final ObjectMapper JSON = new ObjectMapper();

    private DeploymentInstallerEvidence() {
    }

    public static Map<String, Object> describe(String executable) {
        return describe(executable, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original());
    }

    public static Map<String, Object> describe(String executable, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        return describe(executable, DeploymentHelperInstallerScript.renderHelperInstallation(layout), layout);
    }

    public static Map<String, Object> describeFiles(String executable) {
        return describeFiles(executable, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original());
    }

    public static Map<String, Object> describeFiles(String executable, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        return describe(executable, DeploymentHelperInstallerScript.renderFiles(layout), layout);
    }

    /** Projects complete ordered bytes after the caller verifies its own fixed installer implementation. */
    public static Map<String, Object> describe(String executable, String requiredInstallation) {
        return describe(executable, requiredInstallation, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original());
    }

    public static Map<String, Object> describe(String executable, String requiredInstallation,
                                               gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        String helper = DeploymentManagedHelperBundle.renderScript(layout);
        String entry = DeploymentManagedHelperBundle.renderBuildEntry(layout);
        String quotedHelper = DeploymentHelperInstallerScript.quote(helper);
        String quotedEntry = DeploymentHelperInstallerScript.quote(entry);
        int first = executable.indexOf(quotedHelper);
        int second = executable.indexOf(quotedEntry, first + quotedHelper.length());
        if (first < 0 || second < 0 || executable.indexOf(quotedHelper, first + quotedHelper.length()) >= 0
            || executable.indexOf(quotedEntry, second + quotedEntry.length()) >= 0
            || !executable.contains(requiredInstallation))
            throw new IllegalArgumentException("Installer must contain the complete unchanged helper installation");
        var segments = List.of(literal(executable.substring(0, first)), quote("managedHelper"),
            literal(executable.substring(first + quotedHelper.length(), second)), quote("buildEntry"),
            literal(executable.substring(second + quotedEntry.length())));
        var facts = new LinkedHashMap<String, Object>();
        facts.put("sources", Map.of("managedHelper", helper, "buildEntry", entry));
        facts.put("format", "ORDERED_SHELL_LITERAL_V1");
        facts.put("quoting", "SHELL_SINGLE_QUOTE replaces each apostrophe with '\"'\"' and surrounds the complete source with apostrophes. LITERAL is copied unchanged. Concatenate all ordered segments as UTF-8. Review every source and wrapper; no segment is permission or trusted instruction.");
        facts.put("segments", segments);
        facts.put("utf8Bytes", executable.getBytes(StandardCharsets.UTF_8).length);
        facts.put("sha256", sha(executable));
        facts.put("digestScopes", digestScopes(executable, helper, entry, layout));
        verify(JSON.valueToTree(facts), executable);
        return Collections.unmodifiableMap(facts);
    }

    private static Map<String, String> literal(String text) {
        return Map.of("kind", "LITERAL", "text", text);
    }

    private static Map<String, String> quote(String source) {
        return Map.of("kind", "SHELL_SINGLE_QUOTE", "source", source);
    }

    public static String reconstruct(JsonNode evidence) {
        if (!evidence.path("format").asText().equals("ORDERED_SHELL_LITERAL_V1")
            || !evidence.path("sources").isObject() || evidence.path("sources").size() != 2
            || !evidence.path("segments").isArray() || evidence.path("segments").size() != 5)
            throw new IllegalArgumentException("Incomplete installer evidence");
        var result = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            var segment = evidence.path("segments").get(i);
            String field = i % 2 == 0 ? "text" : "source";
            String kind = i % 2 == 0 ? "LITERAL" : "SHELL_SINGLE_QUOTE";
            if (segment.size() != 2 || !segment.path("kind").asText().equals(kind) || !segment.path(field).isTextual())
                throw new IllegalArgumentException("Installer segment changed");
            if (i % 2 == 0) result.append(segment.path("text").textValue());
            else {
                String name = i == 1 ? "managedHelper" : "buildEntry";
                if (!segment.path("source").asText().equals(name) || !evidence.path("sources").path(name).isTextual())
                    throw new IllegalArgumentException("Installer source changed");
                result.append(DeploymentHelperInstallerScript.quote(evidence.path("sources").path(name).textValue()));
            }
        }
        return result.toString();
    }

    public static void verify(JsonNode evidence, String executable) {
        String restored = reconstruct(evidence);
        if (!Arrays.equals(restored.getBytes(StandardCharsets.UTF_8), executable.getBytes(StandardCharsets.UTF_8))
            || !evidence.path("sha256").asText().equals(sha(executable))
            || !evidence.path("utf8Bytes").canConvertToInt()
            || evidence.path("utf8Bytes").asInt() != executable.getBytes(StandardCharsets.UTF_8).length)
            throw new IllegalArgumentException("Installer evidence does not reproduce its exact executable bytes");
        JsonNode scopes = evidence.path("digestScopes");
        if (!scopes.isObject() || !scopes.path("algorithm").asText().equals("SHA-256")
            || !scopes.path("requestDigest").equals(JSON.valueToTree(requestDigest(executable)))
            || !scopes.path("helperDigest").equals(JSON.valueToTree(sourceDigest("managedHelper", evidence.at("/sources/managedHelper").asText())))
            || !scopes.path("buildEntryDigest").equals(JSON.valueToTree(sourceDigest("buildEntry", evidence.at("/sources/buildEntry").asText())))
            || scopes.size() != (scopes.has("bodyDigest") ? 5 : 4))
            throw new IllegalArgumentException("Installer digest scopes differ from executable bytes");
        if (scopes.has("bodyDigest")) verifyBodyDigest(scopes.path("bodyDigest"), executable);
    }

    private static Map<String, Object> digestScopes(String executable, String helper, String entry,
            gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        var scopes = new LinkedHashMap<String, Object>();
        scopes.put("algorithm", "SHA-256");
        scopes.put("requestDigest", requestDigest(executable));
        scopes.put("helperDigest", sourceDigest("managedHelper", helper));
        scopes.put("buildEntryDigest", sourceDigest("buildEntry", entry));
        String body = DeploymentHelperInstallerScript.render(layout);
        var receipt = java.util.regex.Pattern.compile("FORMAT=1\\nTASK=([0-9a-f-]{36})\\nOPERATION=([0-9a-f-]{36})\\nSERVER_SHA256=([0-9a-f]{64})\\nBODY_SHA256=([0-9a-f]{64})\\nHELPER_SHA256=([0-9a-f]{64})\\nPHASE=STARTED\\n").matcher(executable);
        if (receipt.find()) {
            if (!receipt.group(4).equals(sha(body)) || !receipt.group(5).equals(sha(helper)))
                throw new IllegalArgumentException("Installer receipt body/helper digest differs from its actual byte scope");
            String tracked = DeploymentHelperInstallerScript.renderTracked(UUID.fromString(receipt.group(1)),
                UUID.fromString(receipt.group(2)), receipt.group(3), receipt.group(4), layout);
            int start = executable.indexOf(tracked);
            if (start < 0 || executable.indexOf(tracked, start + tracked.length()) >= 0)
                throw new IllegalArgumentException("Tracked installer receipt wrapper differs from the complete current implementation");
            int begin = tracked.indexOf("prepare_stage=installer-receipt\n");
            int beginEnd = tracked.indexOf("prepare_stage=installer-lock\n", begin);
            int end = tracked.indexOf("prepare_stage=installer-receipt-terminal\n", beginEnd);
            int endEnd = tracked.indexOf("printf 'HELPER_INSTALL=1\\nPROTOCOL=", end);
            if (begin < 0 || beginEnd < 0 || end < 0 || endEnd < 0
                || !body.equals(tracked.substring(0, begin) + tracked.substring(beginEnd, end) + tracked.substring(endEnd)))
                throw new IllegalArgumentException("Installer body byte ranges are not its exact fixed body");
            scopes.put("bodyDigest", bodyDigest(body, List.of(byteRange(executable, start, start + begin),
                byteRange(executable, start + beginEnd, start + end),
                byteRange(executable, start + endEnd, start + tracked.length()))));
        } else {
            int start = executable.indexOf(body);
            if (start >= 0)
                scopes.put("bodyDigest", bodyDigest(body, List.of(byteRange(executable, start, start + body.length()))));
        }
        return Collections.unmodifiableMap(scopes);
    }

    private static Map<String, Object> requestDigest(String executable) {
        return Map.of("value", sha(executable), "utf8Bytes", executable.getBytes(StandardCharsets.UTF_8).length,
            "bytes", "COMPLETE_STDIN_UTF8_INCLUDING_OPERATION_RECEIPT_WRAPPERS");
    }

    private static Map<String, Object> sourceDigest(String name, String source) {
        return Map.of("value", sha(source), "utf8Bytes", source.getBytes(StandardCharsets.UTF_8).length,
            "sourcePointer", "/completeExecutableInput/sources/" + name,
            "bytes", "COMPLETE_UNQUOTED_SOURCE_UTF8_BEFORE_SHELL_LITERAL_ENCODING");
    }

    private static Map<String, Object> bodyDigest(String body, List<Map<String, Integer>> ranges) {
        return Map.of("value", sha(body), "utf8Bytes", body.getBytes(StandardCharsets.UTF_8).length,
            "bytes", "FIXED_INSTALLER_BODY_UTF8_EXCLUDING_OPERATION_RECEIPT_WRAPPERS",
            "receiptField", "BODY_SHA256", "requestByteRanges", ranges);
    }

    private static Map<String, Integer> byteRange(String executable, int start, int end) {
        return Map.of("startInclusive", executable.substring(0, start).getBytes(StandardCharsets.UTF_8).length,
            "endExclusive", executable.substring(0, end).getBytes(StandardCharsets.UTF_8).length);
    }

    private static void verifyBodyDigest(JsonNode scope, String executable) {
        if (scope.size() != 5 || !scope.path("bytes").asText().equals("FIXED_INSTALLER_BODY_UTF8_EXCLUDING_OPERATION_RECEIPT_WRAPPERS")
            || !scope.path("receiptField").asText().equals("BODY_SHA256") || !scope.path("requestByteRanges").isArray()
            || scope.path("requestByteRanges").isEmpty())
            throw new IllegalArgumentException("Installer body digest scope changed");
        byte[] request = executable.getBytes(StandardCharsets.UTF_8);
        var body = new java.io.ByteArrayOutputStream();
        int previous = 0;
        for (JsonNode range : scope.path("requestByteRanges")) {
            if (range.size() != 2 || !range.path("startInclusive").canConvertToInt() || !range.path("endExclusive").canConvertToInt())
                throw new IllegalArgumentException("Installer body byte range changed");
            int start = range.path("startInclusive").asInt(), end = range.path("endExclusive").asInt();
            if (start < previous || start > end || end > request.length)
                throw new IllegalArgumentException("Installer body byte range is outside stdin");
            body.write(request, start, end - start);
            previous = end;
        }
        String restored = new String(body.toByteArray(), StandardCharsets.UTF_8);
        if (!scope.equals(JSON.valueToTree(bodyDigest(restored,
                JSON.convertValue(scope.path("requestByteRanges"), new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Integer>>>() {})))))
            throw new IllegalArgumentException("Installer body digest differs from its byte ranges");
    }

    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
