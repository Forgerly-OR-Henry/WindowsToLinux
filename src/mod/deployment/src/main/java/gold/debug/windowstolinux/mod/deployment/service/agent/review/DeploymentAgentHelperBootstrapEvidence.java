package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentInstallerEvidence;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainPrograms;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentHelperInstallerScript;

/** Exact Agent bootstrap source and effects; no permission or inferred execution result. */
public final class DeploymentAgentHelperBootstrapEvidence {
    public static final String OPERATION = "deployment.helper.install.files.custom.v1";

    private DeploymentAgentHelperBootstrapEvidence() {
    }

    public static Map<String, Object> describe(String executable) {
        return describe(executable, DeploymentRemoteLayout.original());
    }

    public static Map<String, Object> describe(String executable, DeploymentRemoteLayout layout) {
        var frame = DeploymentAgentHelperInstallerScript.parseFrame(executable, layout);
        return DeploymentInstallerEvidence.describeCustom(executable,
            DeploymentAgentHelperInstallerScript.render(frame.get(1), layout, new ServerId(frame.get(3)), frame.get(4)),
            DeploymentAgentToolchainPrograms.dependencies(layout), layout);
    }

    public static void requireCurrent(Map<String, ?> current, String executable) {
        requireCurrent(current, executable, DeploymentRemoteLayout.original());
    }

    public static void requireCurrent(Map<String, ?> current, String executable, DeploymentRemoteLayout layout) {
        if (!OPERATION.equals(current.get("operation")) || !(current.get("serverTargets") instanceof java.util.List<?> servers)
            || servers.size() != 1 || !(servers.getFirst() instanceof String serverText)
            || !(current.get("businessRevision") instanceof String revision))
            throw new IllegalArgumentException("Agent bootstrap original binding changed");
        var server = new ServerId(serverText);
        var actual = new LinkedHashSet<ActionTarget>();
        var nodes = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(current.get("resourceTargets"));
        if (!nodes.isArray()) throw new IllegalArgumentException("Agent bootstrap targets missing");
        for (var node : nodes) {
            if (node.size() != 3 || !node.path("serverId").isTextual() || !node.path("kind").isTextual() || !node.path("id").isTextual()
                || !actual.add(new ActionTarget(node.path("serverId").textValue(), node.path("kind").textValue(), node.path("id").textValue())))
                throw new IllegalArgumentException("Agent bootstrap target changed");
        }
        String operation = DeploymentAgentHelperInstallerScript.parseOperation(executable, layout);
        if (!DeploymentAgentHelperInstallerScript.parseFrame(executable, layout).get(3).equals(server.id()))
            throw new IllegalArgumentException("Agent bootstrap server differs from original request");
        if (!actual.equals(targets(server, layout, operation))) throw new IllegalArgumentException("Agent bootstrap effects incomplete");
        describe(executable, layout);
        byte[] bytes = executable.getBytes(StandardCharsets.UTF_8);
        try (var request = new CommandRequest(DeploymentAgentHelperInstallerScript.LAUNCHER, bytes, Duration.ofHours(2), 131072)) {
            if (!RemoteActions.command(server, revision, request).exactAction().equals(current.get("exactAction")))
                throw new IllegalArgumentException("Agent bootstrap executable request changed");
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    public static ActionIntent upgrade(ActionIntent original, CommandRequest request, ServerId server, String revision, String originalOperation) {
        return upgrade(original, request, server, revision, originalOperation, DeploymentRemoteLayout.original());
    }

    public static ActionIntent upgrade(ActionIntent original, CommandRequest request, ServerId server, String revision, String originalOperation, DeploymentRemoteLayout layout) {
        var expected = RemoteActions.command(server, revision, request).withSemantics(new ActionSemantics(OPERATION,
            gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelper.installationTargets(server, layout)));
        if (!original.equals(expected) || !request.script().equals(DeploymentAgentHelperInstallerScript.LAUNCHER)
            || !request.timeout().equals(Duration.ofHours(2)) || request.outputLimitBytes() != 131072)
            throw new IllegalArgumentException("Agent bootstrap differs from its original fixed request");
        byte[] bytes = request.stdin();
        try {
            String source = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            var frame = DeploymentAgentHelperInstallerScript.parseFrame(source, layout);
            if (!frame.get(1).equals(originalOperation) || !frame.get(3).equals(server.id()))
                throw new IllegalArgumentException("Agent bootstrap operation differs from its original attempt");
            describe(source, layout);
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IllegalArgumentException("Agent bootstrap encoding changed", invalid);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
        return original.withSemantics(new ActionSemantics(OPERATION, targets(server, layout, originalOperation)));
    }

    public static Set<ActionTarget> targets(ServerId server, String operation) {
        return targets(server, DeploymentRemoteLayout.original(), operation);
    }

    public static Set<ActionTarget> targets(ServerId server, DeploymentRemoteLayout layout, String operation) {
        var targets = new LinkedHashSet<ActionTarget>();
        targets.add(new ActionTarget(server.id(), "deployment-helper", layout.helperPath()));
        targets.add(new ActionTarget(server.id(), "directory", layout.paths().helperDirectory()));
        targets.add(new ActionTarget(server.id(), "file", layout.buildEntry()));
        for (String root : java.util.List.of(layout.paths().helperDirectory(), layout.paths().applicationsRoot(), layout.paths().stateRoot(),
            layout.paths().configurationRoot(), layout.paths().dataRoot())) targets.add(new ActionTarget(server.id(), "directory", root));
        targets.add(new ActionTarget(server.id(), "file", gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentRemoteLayoutAdmission.UNIT_PATH));
        targets.add(new ActionTarget(server.id(), "systemd-service", gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentRemoteLayoutAdmission.UNIT_NAME));
        targets.add(new ActionTarget(server.id(), "service-manager", "systemd"));
        targets.addAll(gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies.lifecycleTargets(server, operation, layout));
        for (String manager : java.util.List.of("APT", "DNF")) {
            targets.add(new ActionTarget(server.id(), "system-package-index", manager + ":configured-authenticated-sources"));
            targets.add(new ActionTarget(server.id(), "system-package-transaction", manager + ":authenticated-dependency-closure:HELPER_RUNTIME"));
            for (String name : DeploymentAgentToolchainPrograms.packages(manager, "HELPER_RUNTIME"))
                targets.add(new ActionTarget(server.id(), "system-package", name));
        }
        return Set.copyOf(targets);
    }

    /** Bounded source-derived navigation; complete current executable evidence remains authoritative. */
    public static Map<String, Object> navigation(String executable) {
        return navigation(executable, DeploymentRemoteLayout.original());
    }

    public static Map<String, Object> navigation(String executable, DeploymentRemoteLayout layout) {
        var frame = DeploymentAgentHelperInstallerScript.parseFrame(executable, layout);
        var evidence = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(describe(executable, layout));
        String tailPointer = DeploymentInstallerEvidence.tailPointer(evidence);
        String tail = evidence.at(tailPointer).textValue();
        String worker = evidence.at("/sources/dependencyWorker").textValue();
        String workerPointer = "/completeExecutableInput/sources/dependencyWorker";
        int jsonSegment = DeploymentInstallerEvidence.isMirrored(evidence) ? 3 : 1;
        int helperSegment = jsonSegment + 4;
        var prefixParts = new java.util.ArrayList<Map<String, Object>>();
        for (int index = 0; index < helperSegment; index++)
            prefixParts.add(part(evidence, executable, index, 0, encodedSegment(evidence, index).length()));
        var wrappers = new java.util.ArrayList<Map<String, Object>>();
        for (String name : java.util.List.of("files_trusted_directory", "files_trusted_runtime_path", "files_check_one", "files_check", "files_check_runtime")) {
            String first = name + "() {\n";
            int index = literalContaining(evidence, first);
            String literal = evidence.at("/segments/" + index + "/text").textValue();
            int start = literal.indexOf(first);
            int end = literal.indexOf("\n}\n", start);
            if (end < 0) throw new IllegalArgumentException("Bootstrap wrapper source location missing");
            var location = new java.util.LinkedHashMap<>(location("/completeExecutableInput/segments/" + index + "/text", literal, literal.substring(start, end + 2)));
            location.put("function", name);
            wrappers.add(Map.copyOf(location));
        }
        String program = DeploymentAgentToolchainPrograms.dependencies(layout);
        int assignmentStart = encodedSegment(evidence, jsonSegment - 1).length() - "DEPENDENCY_WORKER_SOURCE = ".length();
        int programStart = segmentStart(evidence, jsonSegment - 1) + assignmentStart;
        var dependencyProgram = new java.util.LinkedHashMap<String, Object>();
        dependencyProgram.put("characters", program.length());
        dependencyProgram.put("utf8Bytes", program.getBytes(StandardCharsets.UTF_8).length);
        dependencyProgram.put("sha256", java.util.HexFormat.of().formatHex(sha256(program)));
        dependencyProgram.put("stdinByteRange", byteRange(executable, programStart, programStart + program.length()));
        dependencyProgram.put("parts", java.util.List.of(
            part(evidence, executable, jsonSegment - 1, assignmentStart, "DEPENDENCY_WORKER_SOURCE = ".length()),
            part(evidence, executable, jsonSegment, 0, encodedSegment(evidence, jsonSegment).length()),
            part(evidence, executable, jsonSegment + 1, 0, 1),
            part(evidence, executable, jsonSegment + 2, 0, worker.length())));
        var navigation = new java.util.LinkedHashMap<String, Object>();
        navigation.put("profile", "HELPER_RUNTIME");
        navigation.put("launcher", DeploymentAgentHelperInstallerScript.LAUNCHER);
        navigation.put("prefixParts", java.util.List.copyOf(prefixParts));
        navigation.put("dependencyProgram", Map.copyOf(dependencyProgram));
        navigation.put("dependencySource", location(workerPointer, worker, worker));
        int firstFunction = worker.indexOf("\ndef ");
        if (firstFunction < 0) throw new IllegalArgumentException("Dependency worker preamble missing");
        navigation.put("dependencyPreamble", location(workerPointer, worker, worker.substring(0, firstFunction + 1)));
        navigation.put("wrapperLocations", java.util.List.copyOf(wrappers));
        navigation.put("dependencyLocations", gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainInputEvidence
                .dependencySourceLocations(worker, workerPointer));
        navigation.put("invocations", java.util.List.of(literalLocation(evidence,
                    "  /usr/bin/python3 -I -S - " + frame.stream().map(value -> "'" + value + "'").collect(java.util.stream.Collectors.joining(" ")) + " <<'WTL_AGENT_HELPER_DEPENDENCIES'"),
                location("/completeExecutableInput" + tailPointer, tail,
                    "helper_probe=\"$('" + layout.helperPath() + "' probe)\"")));
        navigation.put("meaning", "Navigation into current canonical guarded file installation only. Concatenate the ordered prefixParts using their declared encodings; dependencyProgram binds its assignment prefix, JSON_STRING worker use, LF and SOURCE_TEXT worker use to exact stdin byte ranges. The complete dependencyWorker source includes the fixed HELPER_RUNTIME package table and operations root; no installed helper or vendor worker runs that Python transaction. Existing helper/build-entry conflicts are checked before transactions and installation. The installer later calls the complete source's probe entrypoint. Native OS tools and package database are the bootstrap trust baseline, not historical signature proof or protection from malicious root OS changes. DNF HELPER_RUNTIME does not enable CRB. These locations replace no executable evidence, independent verdict, current target or authorization check.");
        return Map.copyOf(navigation);
    }

    private static Map<String, Object> literalLocation(com.fasterxml.jackson.databind.JsonNode evidence, String fragment) {
        int index = literalContaining(evidence, fragment);
        return location("/completeExecutableInput/segments/" + index + "/text",
            evidence.at("/segments/" + index + "/text").textValue(), fragment);
    }

    private static int literalContaining(com.fasterxml.jackson.databind.JsonNode evidence, String fragment) {
        int found = -1;
        for (int index = 0; index < evidence.path("segments").size(); index += 2) {
            String literal = evidence.at("/segments/" + index + "/text").textValue();
            int start = literal.indexOf(fragment);
            if (start < 0) continue;
            if (found >= 0 || literal.indexOf(fragment, start + 1) >= 0)
                throw new IllegalArgumentException("Bootstrap literal source location ambiguous");
            found = index;
        }
        if (found < 0) throw new IllegalArgumentException("Bootstrap literal source location missing");
        return found;
    }

    private static Map<String, Object> part(com.fasterxml.jackson.databind.JsonNode evidence, String executable, int index, int start, int characters) {
        var segment = evidence.path("segments").get(index);
        String encoded = encodedSegment(evidence, index);
        String fragment = encoded.substring(start, start + characters);
        var part = new java.util.LinkedHashMap<String, Object>();
        part.put("segmentPointer", "/completeExecutableInput/segments/" + index);
        part.put("encoding", segment.path("kind").textValue());
        part.put("encodedStartCharacter", start);
        part.put("encodedCharacters", characters);
        part.put("encodedUtf8Bytes", fragment.getBytes(StandardCharsets.UTF_8).length);
        part.put("encodedSha256", java.util.HexFormat.of().formatHex(sha256(fragment)));
        int stdinStart = segmentStart(evidence, index) + start;
        part.put("stdinByteRange", byteRange(executable, stdinStart, stdinStart + characters));
        if (segment.path("kind").asText().equals("LITERAL"))
            part.putAll(location("/completeExecutableInput/segments/" + index + "/text", encoded, fragment));
        else {
            String pointer = "/sources/" + segment.path("source").textValue();
            String source = evidence.at(pointer).textValue();
            part.putAll(location("/completeExecutableInput" + pointer, source, source));
        }
        return Map.copyOf(part);
    }

    private static int segmentStart(com.fasterxml.jackson.databind.JsonNode evidence, int index) {
        int start = 0;
        for (int before = 0; before < index; before++) start += encodedSegment(evidence, before).length();
        return start;
    }

    private static String encodedSegment(com.fasterxml.jackson.databind.JsonNode evidence, int index) {
        var segment = evidence.path("segments").get(index);
        if (segment.path("kind").asText().equals("LITERAL")) return segment.path("text").textValue();
        String source = evidence.path("sources").path(segment.path("source").textValue()).textValue();
        return switch (segment.path("kind").asText()) {
            case "SHELL_SINGLE_QUOTE" -> gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript.quote(source);
            case "SOURCE_TEXT" -> source;
            case "JSON_STRING" -> {
                try { yield new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(source); }
                catch (java.io.IOException failure) { throw new IllegalArgumentException("Dependency worker navigation encoding failed", failure); }
            }
            default -> throw new IllegalArgumentException("Bootstrap source encoding changed");
        };
    }

    private static Map<String, Integer> byteRange(String executable, int start, int end) {
        return Map.of("startInclusive", executable.substring(0, start).getBytes(StandardCharsets.UTF_8).length,
            "endExclusive", executable.substring(0, end).getBytes(StandardCharsets.UTF_8).length);
    }

    private static Map<String, Object> location(String pointer, String source, String fragment) {
        int start = source.indexOf(fragment);
        if (start < 0 || source.indexOf(fragment, start + 1) >= 0)
            throw new IllegalArgumentException("Bootstrap source location missing or ambiguous");
        int first = Math.toIntExact(1 + source.substring(0, start).chars().filter(value -> value == '\n').count());
        int last = Math.toIntExact(first + fragment.stripTrailing().chars().filter(value -> value == '\n').count());
        return Map.of("sourcePointer", pointer, "startLine", first, "endLine", last,
            "startCharacter", start, "characters", fragment.length(),
            "utf8Bytes", fragment.getBytes(StandardCharsets.UTF_8).length,
            "sha256", java.util.HexFormat.of().formatHex(sha256(fragment)));
    }

    private static byte[] sha256(String source) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK SHA-256 missing", impossible);
        }
    }
}
