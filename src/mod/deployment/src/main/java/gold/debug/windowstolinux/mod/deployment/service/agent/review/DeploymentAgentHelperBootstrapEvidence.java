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
        try (var request = new CommandRequest("exec /bin/bash --noprofile --norc -s", bytes, Duration.ofHours(2), 131072)) {
            if (!RemoteActions.command(server, revision, request).exactAction().equals(current.get("exactAction")))
                throw new IllegalArgumentException("Agent bootstrap executable request changed");
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    public static ActionIntent upgrade(ActionIntent original, CommandRequest request, ServerId server, String revision, String originalOperation) {
        var expected = RemoteActions.command(server, revision, request).withSemantics(new ActionSemantics(OPERATION,
            Set.of(new ActionTarget(server.id(), "deployment-helper", DeploymentManagedHelperBundle.PATH))));
        if (!original.equals(expected) || !request.script().equals("exec /bin/bash --noprofile --norc -s")
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
}
