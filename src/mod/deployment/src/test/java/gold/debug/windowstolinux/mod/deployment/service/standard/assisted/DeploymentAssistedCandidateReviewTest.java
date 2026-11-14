package gold.debug.windowstolinux.mod.deployment.service.standard.assisted;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentCandidateChannel;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;

class DeploymentAssistedCandidateReviewTest {
    private final ServerId server = new ServerId("server");
    private final UUID originalTask = UUID.randomUUID();
    private final DeploymentRemoteLayout layout = DeploymentRemoteLayout.original();
    private final String sha256 = "a".repeat(64);
    private final TaskContext task = (TaskContext) Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[]{TaskContext.class}, (proxy, method, args) -> {
            if (method.getName().equals("id")) return originalTask;
            throw new AssertionError(method.getName());
        });
    private final Map<String, Map<String, Object>> sources = Map.of("demo", Map.of("server", server.id(),
        "task", originalTask.toString(), "remoteLayoutSha256", layout.digest(), "archiveBytes", 14592L,
        "archiveSha256", sha256, "sourceManifestSha256", "b".repeat(64)));

    @Test
    void exposesFullExpectedArchiveReplyOnlyForExactGuardAndOriginalCapture() {
        String candidate = new DeploymentRemoteWorkspace("demo", sha256, layout).candidateId();
        String script = DeploymentCandidateChannel.archiveVerificationScript(layout, "demo", candidate, originalTask);
        var facts = review(candidate, script, DeploymentAssistedCandidateReview.VERIFY, Set.of());
        assertEquals("CANDIDATE_STATE=owned\nBUILD_ACTIVE=no\n", facts.get("helperStdoutPrefix"));
        assertEquals("CANDIDATE_STATE=owned\nBUILD_ACTIVE=no\nBYTES=14592\nSHA256=" + sha256 + "\nOWNER=0\n", facts.get("expectedStdout"));
        var instructions = DeploymentAssistedCandidateReview.verificationInstructions();
        assertTrue(instructions.contains("helperStdoutPrefix"));
        assertTrue(instructions.contains("candidateObservation.expectedStdout against the complete outer command stdout"));
        assertEquals(sha256, facts.get("archiveSha256"));
        assertFalse(facts.containsKey("commandGrant"));
        assertThrows(IllegalArgumentException.class, () -> review(candidate, script + ":\n",
            DeploymentAssistedCandidateReview.VERIFY, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> review(candidate,
            script.replace(originalTask.toString(), UUID.randomUUID().toString()), DeploymentAssistedCandidateReview.VERIFY, Set.of()));
    }

    @Test
    void verifiesAlternateCandidateOnlyForTheOriginalDurableOperation() {
        UUID operation = UUID.randomUUID();
        String candidate = new DeploymentRemoteWorkspace("demo", sha256, Optional.of(operation), layout).candidateId();
        String script = DeploymentCandidateChannel.archiveVerificationScript(layout, "demo", candidate, originalTask);
        assertThrows(DeploymentAssistedCandidateReview.AlternateCandidate.class, () ->
            review(candidate, script, DeploymentAssistedCandidateReview.VERIFY, Set.of(UUID.randomUUID())));
        assertEquals(sha256, review(candidate, script, DeploymentAssistedCandidateReview.VERIFY, Set.of(operation)).get("archiveSha256"));
    }

    @Test
    void describesCandidateQueryWithoutClaimingObservedOwnership() {
        String candidate = new DeploymentRemoteWorkspace("demo", sha256, layout).candidateId();
        String script = DeploymentCandidateChannel.candidateQueryScript(layout, "demo", candidate, originalTask);
        var facts = review(candidate, script, DeploymentAssistedCandidateReview.QUERY, Set.of());
        assertEquals("CANDIDATE_STATE=owned\nBUILD_ACTIVE=no\n", facts.get("expectedStdout"));
        assertFalse(facts.containsKey("helperStdoutPrefix"));
        assertFalse(facts.containsKey("archiveSha256"));
        assertThrows(IllegalArgumentException.class, () -> review(candidate, script.replace("candidate-query-task", "candidate-cleanup-task"),
            DeploymentAssistedCandidateReview.QUERY, Set.of()));
    }

    private Map<String, Object> review(String candidate, String script, String operation, Set<UUID> reviewedOperations) {
        long seconds = operation.equals(DeploymentAssistedCandidateReview.VERIFY) ? 30 : 20;
        try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(script, layout),
                new byte[0], Duration.ofSeconds(seconds), 4096)) {
            var target = new ActionTarget(server.id(), "deployment-candidate", layout.candidateRoot(candidate));
            var intent = RemoteActions.command(server, "revision", request).withSemantics(new ActionSemantics(operation, Set.of(target)));
            return DeploymentAssistedCandidateReview.facts(intent, request, task, layout, sources, reviewedOperations);
        }
    }
}
