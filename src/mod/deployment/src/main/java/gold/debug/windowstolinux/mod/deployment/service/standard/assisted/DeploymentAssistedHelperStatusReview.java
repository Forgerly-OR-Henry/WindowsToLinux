package gold.debug.windowstolinux.mod.deployment.service.standard.assisted;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelCommandIdentity;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelper;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.setup.DeploymentProtocolBootstrapActionLog;

/** Original-checkpoint evidence for one exact read-only bootstrap receipt query; never an approval. */
final class DeploymentAssistedHelperStatusReview {
    private DeploymentAssistedHelperStatusReview() {}

    static Map<String, Object> facts(TaskService tasks, TaskContext task, ActionIntent intent,
                                    CommandRequest request, DeploymentRemoteLayout layout) throws ApiException {
        var journal = DeploymentProtocolBootstrapActionLog.find(tasks, task)
            .orElseThrow(() -> new IllegalArgumentException("Original helper installation journal is required"));
        var original = journal.facts();
        var server = new ServerId(original.serverId());
        if (!intent.serverIds().equals(List.of(original.serverId())) || !original.layout().equals(layout)
            || !original.phase().equals("DISPATCHED")
            || !original.serverDigest().equals(DeploymentModelCommandIdentity.digest(original.serverId()))
            || !original.bodyDigest().equals(DeploymentModelCommandIdentity.digest(DeploymentHelperInstallerScript.render(layout)))
            || !original.helperDigest().equals(DeploymentManagedHelperBundle.helperSha256(layout))
            || !original.requestDigest().equals(DeploymentModelCommandIdentity.digest(DeploymentHelperInstallerScript.renderTracked(
                task.id(), original.operationId(), original.serverDigest(), original.bodyDigest(), layout))))
            throw new IllegalArgumentException("Original helper installation task, server, phase, layout or digests differ");
        byte[] expected = DeploymentHelperInstallerScript.renderStatus(original.operationId(), layout).getBytes(StandardCharsets.UTF_8);
        byte[] actual = request.stdin();
        try (var canonical = new CommandRequest("exec /bin/bash --noprofile --norc -s", expected, Duration.ofSeconds(30), 2048)) {
            var expectedIntent = RemoteActions.command(server, intent.revision(), canonical).withSemantics(new ActionSemantics(
                "deployment.helper.install.status.v1", DeploymentManagedHelper.installationStatusTargets(server, layout, original.operationId())));
            if (!Arrays.equals(expected, actual) || !request.script().equals(canonical.script())
                || !request.timeout().equals(canonical.timeout()) || request.outputLimitBytes() != canonical.outputLimitBytes()
                || !expectedIntent.equals(intent))
                throw new IllegalArgumentException("Helper receipt query differs from the original exact operation and resources");
        } finally {
            Arrays.fill(expected, (byte) 0);
            Arrays.fill(actual, (byte) 0);
        }
        var facts = new LinkedHashMap<String, Object>();
        facts.put("task", task.id().toString());
        facts.put("server", original.serverId());
        facts.put("operation", original.operationId().toString());
        facts.put("checkpoint", DeploymentProtocolBootstrapActionLog.KEY);
        facts.put("phase", original.phase());
        facts.put("remoteLayoutSha256", layout.digest());
        facts.put("serverDigest", original.serverDigest());
        facts.put("bodyDigest", original.bodyDigest());
        facts.put("requestDigest", original.requestDigest());
        facts.put("helperDigest", original.helperDigest());
        facts.put("statusInputSha256", DeploymentModelCommandIdentity.digest(DeploymentHelperInstallerScript.renderStatus(original.operationId(), layout)));
        facts.put("receiptPath", layout.helperInstallOperationsRoot() + "/" + original.operationId() + ".receipt");
        facts.put("expectedReceipt", DeploymentHelperInstallerScript.receipt(task.id(), original.operationId(),
            original.serverDigest(), original.bodyDigest(), "SUCCEEDED", layout));
        facts.put("clientVerification", Map.of(
            "implementation", "DeploymentProtocolBootstrap.settle / knownSuccessfulExit",
            "statusResultRequirements", List.of("outcome=SUCCEEDED", "value present", "exitCode=0", "truncated=false", "stderr empty",
                "stdout.equals(expectedReceipt), including every field and newline"),
            "receiptMismatchOutcome", "UNKNOWN; original DISPATCHED journal remains retained",
            "afterReceiptMatch", "A separately reviewed helper.probePlatform must confirm the current helper identity and platform prerequisites before journal.terminal(true) and journal.close(). A failed probe retains UNKNOWN.",
            "effects", "This request only checks root-owned original receipt ancestry, permissions, bounded length and reads that receipt. It does not invoke the installed helper, install packages, replay installation or continue deployment."));
        facts.put("meaning", "Read from this authentic original task's retained pre-dispatch bootstrap checkpoint. Server, operation, original layout, fixed body/full installer/helper digests, complete canonical status stdin, launcher, limits, exact action and every declared target were compared before this review. The original helper installation precedes toolchain preparation; this is its same-task receipt reconciliation, not a different application or unrelated installation. bodyDigest identifies the fixed installer without receipt wrappers; requestDigest identifies its complete original stdin; helperDigest identifies the unquoted helper source. expectedReceipt is the client's expected result, not an observed receipt or installation proof. Original workflow selection remains authoritative. Independently review the complete current read and selected user scope; none of these facts grants approval, permission, a retry or installation replay.");
        return Map.copyOf(facts);
    }
}
