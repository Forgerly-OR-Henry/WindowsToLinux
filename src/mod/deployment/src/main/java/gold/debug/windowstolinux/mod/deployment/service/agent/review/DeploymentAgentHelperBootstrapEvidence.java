package gold.debug.windowstolinux.mod.deployment.service.agent.review;

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
        String operation = DeploymentAgentHelperInstallerScript.parseOperation(executable);
        return DeploymentInstallerEvidence.describe(executable, DeploymentAgentHelperInstallerScript.render(operation));
    }

    public static void requireCurrent(Map<String, ?> current, String executable) {
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
        if (!actual.equals(targets(server))) throw new IllegalArgumentException("Agent bootstrap effects incomplete");
        describe(executable);
        byte[] bytes = executable.getBytes(StandardCharsets.UTF_8);
        try (var request = new CommandRequest(DeploymentAgentHelperInstallerScript.LAUNCHER, bytes, Duration.ofHours(2), 131072)) {
            if (!RemoteActions.command(server, revision, request).exactAction().equals(current.get("exactAction")))
                throw new IllegalArgumentException("Agent bootstrap executable request changed");
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    public static ActionIntent upgrade(ActionIntent original, CommandRequest request, ServerId server, String revision, String originalOperation) {
        var expected = RemoteActions.command(server, revision, request).withSemantics(new ActionSemantics(OPERATION,
            Set.of(new ActionTarget(server.id(), "deployment-helper", DeploymentManagedHelperBundle.PATH))));
        if (!original.equals(expected) || !request.script().equals(DeploymentAgentHelperInstallerScript.LAUNCHER)
            || !request.timeout().equals(Duration.ofHours(2)) || request.outputLimitBytes() != 131072)
            throw new IllegalArgumentException("Agent bootstrap differs from its original fixed request");
        byte[] bytes = request.stdin();
        try {
            String source = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            if (!DeploymentAgentHelperInstallerScript.parseOperation(source).equals(originalOperation))
                throw new IllegalArgumentException("Agent bootstrap operation differs from its original attempt");
            describe(source);
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IllegalArgumentException("Agent bootstrap encoding changed", invalid);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
        return original.withSemantics(new ActionSemantics(OPERATION, targets(server)));
    }

    public static Set<ActionTarget> targets(ServerId server) {
        var targets = new LinkedHashSet<ActionTarget>();
        targets.add(new ActionTarget(server.id(), "deployment-helper", DeploymentManagedHelperBundle.PATH));
        targets.add(new ActionTarget(server.id(), "directory", DeploymentManagedHelperBundle.DIRECTORY));
        targets.add(new ActionTarget(server.id(), "file", DeploymentManagedHelperBundle.BUILD_ENTRY_PATH));
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
        String operation = DeploymentAgentHelperInstallerScript.parseOperation(executable);
        var evidence = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(describe(executable));
        String prefix = evidence.at("/segments/0/text").textValue();
        String tail = evidence.at("/segments/4/text").textValue();
        String pointer = "/completeExecutableInput/segments/0/text";
        var wrappers = new java.util.ArrayList<Map<String, Object>>();
        for (String name : java.util.List.of("files_trusted_directory", "files_trusted_runtime_path", "files_check_one", "files_check", "files_check_runtime")) {
            String first = name + "() {\n";
            int start = prefix.indexOf(first);
            int end = start < 0 ? -1 : prefix.indexOf("\n}\n", start);
            if (end < 0) throw new IllegalArgumentException("Bootstrap wrapper source location missing");
            var location = new java.util.LinkedHashMap<>(location(pointer, prefix, prefix.substring(start, end + 2)));
            location.put("function", name);
            wrappers.add(Map.copyOf(location));
        }
        String program = DeploymentAgentToolchainPrograms.dependencies();
        return Map.of("profile", "HELPER_RUNTIME", "launcher", DeploymentAgentHelperInstallerScript.LAUNCHER,
            "prefixSource", location(pointer, prefix, prefix),
            "dependencyProgram", location(pointer, prefix, program),
            "wrapperLocations", java.util.List.copyOf(wrappers),
            "dependencyLocations", gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainInputEvidence
                .dependencySourceLocations(prefix, pointer),
            "invocations", java.util.List.of(location(pointer, prefix,
                    "  /usr/bin/python3 -I -S - 'HELPER_RUNTIME' '" + operation + "' <<'WTL_AGENT_HELPER_DEPENDENCIES'"),
                location("/completeExecutableInput/segments/4/text", tail,
                    "helper_probe=\"$('" + DeploymentManagedHelperBundle.PATH + "' probe)\"")),
            "meaning", "Navigation into current canonical guarded file installation only. The complete prefix includes the original embedded dependency program and fixed HELPER_RUNTIME package table; no installed helper or vendor worker runs that Python transaction. Existing helper/build-entry conflicts are checked before transactions and installation. The installer later calls the complete source's probe entrypoint. Native OS tools and package database are the bootstrap trust baseline, not historical signature proof or protection from malicious root OS changes. DNF HELPER_RUNTIME does not enable CRB. These locations replace no executable evidence, independent verdict, current target or authorization check.");
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
