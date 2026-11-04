package gold.debug.windowstolinux.mod.deployment.service.standard.assisted;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelCommandIdentity;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelper;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.setup.DeploymentProtocolBootstrapActionLog;
import org.junit.jupiter.api.Test;

class DeploymentAssistedHelperStatusReviewTest {
    @Test
    void originalStatusFactsRequireExactIsolatedLauncherInputLimitsAndAction() throws Exception {
        var task = new NativeAiFixture().task;
        var server = new ServerId("original-status");
        var layout = DeploymentRemoteLayout.original();
        var operation = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        String serverDigest = DeploymentModelCommandIdentity.digest(server.id());
        String bodyDigest = DeploymentModelCommandIdentity.digest(DeploymentHelperInstallerScript.render(layout));
        var facts = new DeploymentProtocolBootstrapActionLog.Facts(server.id(), operation, serverDigest, bodyDigest,
            DeploymentModelCommandIdentity.digest(DeploymentHelperInstallerScript.renderTracked(task.id(), operation,
                serverDigest, bodyDigest, layout)), DeploymentManagedHelperBundle.helperSha256(layout), "DISPATCHED", layout);
        var snapshot = new TaskCheckpoint.Snapshot(1, DeploymentProtocolBootstrapActionLog.encode(task.id(), facts), false);
        TaskCheckpoint checkpoint = (TaskCheckpoint) Proxy.newProxyInstance(TaskCheckpoint.class.getClassLoader(),
            new Class<?>[]{TaskCheckpoint.class}, (proxy, method, args) -> {
                if (method.getName().equals("snapshot")) return snapshot;
                throw new AssertionError("Read-only original checkpoint expected");
            });
        TaskService tasks = (TaskService) Proxy.newProxyInstance(TaskService.class.getClassLoader(),
            new Class<?>[]{TaskService.class}, (proxy, method, args) -> {
                if (method.getName().equals("findCheckpoint") && args[0] == task) {
                    if (args[1].equals("deployment.config.remote-layout.v1")) return Optional.empty();
                    if (args[1].equals(DeploymentProtocolBootstrapActionLog.KEY)) return Optional.of(checkpoint);
                }
                throw new AssertionError("No Runtime, writes or other task access expected");
            });
        byte[] body = DeploymentHelperInstallerScript.renderStatus(operation, layout).getBytes(StandardCharsets.UTF_8);
        try (var canonical = new CommandRequest(DeploymentHelperInstallerScript.LAUNCHER, body, Duration.ofSeconds(30), 2048)) {
            var intent = RemoteActions.command(server, "revision", canonical).withSemantics(new ActionSemantics(
                "deployment.helper.install.status.v1", DeploymentManagedHelper.installationStatusTargets(server, layout, operation)));
            var accepted = DeploymentAssistedHelperStatusReview.facts(tasks, task, intent, canonical, layout);
            assertEquals(facts.requestDigest(), accepted.get("requestDigest"), "The journal digest remains the complete stdin digest");
            assertEquals(DeploymentModelCommandIdentity.digest(new String(body, StandardCharsets.UTF_8)), accepted.get("statusInputSha256"));
            for (String fault : List.of("old", "missing-i", "path", "missing-p", "body", "timeout", "limit", "prior-binding")) {
                String launcher = switch (fault) {
                    case "old" -> "exec /bin/bash --noprofile --norc -s";
                    case "missing-i" -> DeploymentHelperInstallerScript.LAUNCHER.replace(" -i ", " ");
                    case "path" -> DeploymentHelperInstallerScript.LAUNCHER.replace("PATH=/usr/sbin:/usr/bin:/sbin:/bin", "PATH=/untrusted:/usr/bin:/bin");
                    case "missing-p", "prior-binding" -> DeploymentHelperInstallerScript.LAUNCHER.replace(" -p ", " ");
                    default -> DeploymentHelperInstallerScript.LAUNCHER;
                };
                try (var changed = new CommandRequest(launcher, fault.equals("body") ? new byte[]{1} : body,
                    Duration.ofSeconds(fault.equals("timeout") ? 31 : 30), fault.equals("limit") ? 2049 : 2048)) {
                    var changedIntent = fault.equals("prior-binding") ? intent : RemoteActions.command(server, "revision", changed)
                        .withSemantics(intent.semantics().orElseThrow());
                    assertThrows(IllegalArgumentException.class,
                        () -> DeploymentAssistedHelperStatusReview.facts(tasks, task, changedIntent, changed, layout), fault);
                }
            }
        }
    }
}
