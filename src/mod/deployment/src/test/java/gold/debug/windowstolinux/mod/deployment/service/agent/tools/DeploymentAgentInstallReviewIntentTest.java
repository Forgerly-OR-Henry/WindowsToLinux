package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentInstallReviewIntent;
import org.junit.jupiter.api.Test;

class DeploymentAgentInstallReviewIntentTest {
    private final ServerId server = new ServerId("Server-Exact");

    private static final String REVISION = "a".repeat(64);

    private Set<ActionTarget> originalTargets() {
        return gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelper.installationTargets(server, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original());
    }

    private ActionIntent original(CommandRequest request) {
        return RemoteActions.command(server, REVISION, request).withSemantics(new ActionSemantics("deployment.helper.install.v1", originalTargets()));
    }

    @Test
    void exactAgentInstallAddsDeclaredTargetsWithoutChangingSharedIntentOrCommandBytes() throws Exception {
        var fixture = new NativeAiFixture();
        byte[] body = DeploymentHelperInstallerScript.render().getBytes(StandardCharsets.UTF_8);
        try (var request = new CommandRequest("exec /bin/bash --noprofile --norc -s", body, Duration.ofHours(2), 131072)) {
            var original = original(request);
            assertTrue(DeploymentAgentRemoteTools.ownsInstallReview(original, request, fixture.task, fixture.task, server, REVISION));
            var expanded = DeploymentAgentInstallReviewIntent.upgrade(original, request, server, REVISION);
            assertEquals(original, expanded.withSemantics(original.semantics().orElseThrow()));
            assertEquals(originalTargets(), original.semantics().orElseThrow().targets());
            assertEquals(Set.of(
                new ActionTarget(server.id(), "deployment-helper", DeploymentManagedHelperBundle.PATH),
                new ActionTarget(server.id(), "directory", DeploymentManagedHelperBundle.DIRECTORY),
                new ActionTarget(server.id(), "directory", "/opt/windowstolinux/apps"),
                new ActionTarget(server.id(), "directory", "/var/lib/windowstolinux"),
                new ActionTarget(server.id(), "directory", "/etc/opt/windowstolinux/apps"),
                new ActionTarget(server.id(), "directory", "/var/opt/windowstolinux/apps"),
                new ActionTarget(server.id(), "file", DeploymentManagedHelperBundle.BUILD_ENTRY_PATH),
                new ActionTarget(server.id(), "file", "/etc/systemd/system/windowstolinux-workspace-recovery.service"),
                new ActionTarget(server.id(), "file", "/etc/systemd/system/multi-user.target.wants/windowstolinux-workspace-recovery.service"),
                new ActionTarget(server.id(), "service-manager", "systemd"),
                new ActionTarget(server.id(), "systemd-service", "windowstolinux-workspace-recovery.service"),
                new ActionTarget(server.id(), "package-manager", "apt-get"),
                new ActionTarget(server.id(), "package-manager", "dnf"),
                new ActionTarget(server.id(), "package-set", "apt-get:" + DeploymentHelperInstallerScript.APT_PACKAGES),
                new ActionTarget(server.id(), "package-set", "dnf:" + DeploymentHelperInstallerScript.DNF_PACKAGES)), expanded.semantics().orElseThrow().targets());
            assertArrayEquals(body, request.stdin(), "The pure conversion must not alter or wipe the owned executable input");
            assertFalse(DeploymentAgentRemoteTools.ownsInstallReview(expanded, request, fixture.task, fixture.task, server, REVISION));
            assertFalse(DeploymentAgentRemoteTools.ownsInstallReview(original, request, new NativeAiFixture().task, fixture.task, server, REVISION));
            assertEquals(0, fixture.requests.size());
        } finally {
            Arrays.fill(body, (byte) 0);
        }
    }

    @Test
    void onlyOriginalCurrentUntrackedInstallerCanAcquireTheAgentTargetDescription() throws Exception {
        String installer = DeploymentHelperInstallerScript.render();
        for (String fault : List.of("missing-semantics", "missing-target", "extra-target", "target-server", "foreign-operation", "tracked-operation",
            "expanded-intent", "body", "tracked-body", "launcher", "server", "revision", "exact-action", "summary")) {
            String body = fault.equals("body") ? installer + "printf changed\n" : fault.equals("tracked-body")
                ? DeploymentHelperInstallerScript.renderTracked(UUID.randomUUID(), UUID.randomUUID(), "b".repeat(64), "c".repeat(64)) : installer;
            String launcher = fault.equals("launcher") ? "exec /bin/bash -s" : "exec /bin/bash --noprofile --norc -s";
            try (var request = new CommandRequest(launcher, body.getBytes(StandardCharsets.UTF_8), Duration.ofHours(2), 131072)) {
                var initial = original(request);
                ActionIntent changed = switch (fault) {
                    case "missing-semantics" -> RemoteActions.command(server, REVISION, request);
                    case "missing-target" -> {
                        var targets = new HashSet<>(DeploymentAgentInstallReviewIntent.upgrade(initial, request, server, REVISION).semantics().orElseThrow().targets());
                        targets.remove(new ActionTarget(server.id(), "file", DeploymentManagedHelperBundle.BUILD_ENTRY_PATH));
                        yield initial.withSemantics(new ActionSemantics("deployment.helper.install.v1", targets));
                    }
                    case "extra-target" -> initial.withSemantics(new ActionSemantics("deployment.helper.install.v1",
                        Set.of(new ActionTarget(server.id(), "deployment-helper", DeploymentManagedHelperBundle.PATH), new ActionTarget(server.id(), "file", "/unrelated"))));
                    case "target-server" -> initial.withSemantics(new ActionSemantics("deployment.helper.install.v1",
                        Set.of(new ActionTarget("other-server", "deployment-helper", DeploymentManagedHelperBundle.PATH))));
                    case "foreign-operation" ->
                        initial.withSemantics(new ActionSemantics("deployment.helper.probe.v1", originalTargets()));
                    case "tracked-operation" ->
                        initial.withSemantics(new ActionSemantics("deployment.helper.install.tracked.v1", originalTargets()));
                    case "expanded-intent" ->
                        DeploymentAgentInstallReviewIntent.upgrade(initial, request, server, REVISION);
                    case "server" -> RemoteActions.command(new ServerId("other-server"), REVISION, request)
                        .withSemantics(new ActionSemantics("deployment.helper.install.v1", originalTargets()));
                    case "revision" -> RemoteActions.command(server, "d".repeat(64), request)
                        .withSemantics(new ActionSemantics("deployment.helper.install.v1", originalTargets()));
                    case "exact-action" ->
                        new ActionIntent(initial.type(), initial.serverIds(), initial.target(), initial.revision(),
                            initial.exactAction() + "\nchanged", initial.effectSummary(), initial.semantics());
                    case "summary" ->
                        new ActionIntent(initial.type(), initial.serverIds(), initial.target(), initial.revision(),
                            initial.exactAction(), "Changed effect summary", initial.semantics());
                    default -> initial;
                };
                assertThrows(IllegalArgumentException.class, () -> DeploymentAgentInstallReviewIntent.upgrade(changed, request, server, REVISION), fault);
            }
        }
    }
}
