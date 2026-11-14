package gold.debug.windowstolinux.mod.deployment.service.standard.assisted;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentCandidateChannel;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;

/** Binds candidate observation and the local acceptance condition to the actual reviewed request. */
final class DeploymentAssistedCandidateReview {
    static final String QUERY = "deployment.candidate.query.v1";
    static final String VERIFY = "deployment.candidate.verify.v1";
    private static final Pattern CANDIDATE = Pattern.compile("([a-z0-9][a-z0-9-]{0,62})-[a-f0-9]{16}");

    private DeploymentAssistedCandidateReview() {
    }

    static String verificationInstructions() {
        return """
            For the guarded archive verification, candidate-query-task is the first step of the outer command.
            Its CANDIDATE_STATE and BUILD_ACTIVE lines form helperStdoutPrefix. The outer command checks the exact archive path and appends BYTES, SHA256 and OWNER in that order.
            Compare candidateObservation.expectedStdout against the complete outer command stdout. The standalone candidate query's two-line contract applies only to the nested helper here.
            These are acceptance conditions for the upcoming read. Verify the guard, target, original task and archive association independently for this command.
            """;
    }

    static final class AlternateCandidate extends IllegalArgumentException {
        AlternateCandidate() {
            super("Candidate differs from the original archive and reviewed operations");
        }
    }

    static Map<String, Object> facts(ActionIntent intent, CommandRequest request, TaskContext task,
                                     DeploymentRemoteLayout layout, Map<String, Map<String, Object>> sources,
                                     Set<UUID> reviewedOperations) {
        var semantics = intent.semantics().orElseThrow();
        String operation = semantics.operation();
        if (!Set.of(QUERY, VERIFY).contains(operation) || intent.serverIds().size() != 1 || semantics.targets().size() != 1)
            throw new IllegalArgumentException("Candidate observation scope differs");
        String server = intent.serverIds().getFirst();
        var target = semantics.targets().iterator().next();
        String path = target.id();
        String candidate = path.substring(path.lastIndexOf('/') + 1);
        var name = CANDIDATE.matcher(candidate);
        if (!name.matches() || !target.equals(new ActionTarget(server, "deployment-candidate", layout.candidateRoot(candidate)))
            || !intent.equals(RemoteActions.command(new ServerId(server), intent.revision(), request).withSemantics(semantics)))
            throw new IllegalArgumentException("Candidate observation target or action differs");

        String application = name.group(1);
        boolean archive = operation.equals(VERIFY);
        String expectedBody = archive ? DeploymentCandidateChannel.archiveVerificationScript(layout, application, candidate, task.id())
            : DeploymentCandidateChannel.candidateQueryScript(layout, application, candidate, task.id());
        var maximum = java.time.Duration.ofSeconds(archive ? 30 : 20);
        byte[] input = request.stdin();
        try {
            if (!request.script().equals(DeploymentManagedHelperBundle.verifiedCommand(expectedBody, layout))
                || input.length != 0 || request.outputLimitBytes() != 4096
                || request.timeout().compareTo(maximum) > 0 || !request.timeout().equals(java.time.Duration.ofSeconds(request.timeout().getSeconds())))
                throw new IllegalArgumentException("Candidate observation frame differs");
        } finally {
            Arrays.fill(input, (byte) 0);
        }
        if (!archive)
            return Map.of("task", task.id().toString(), "application", application, "candidate", candidate,
                "expectedStdout", DeploymentCandidateChannel.OWNED_IDLE_STDOUT,
                "meaning", "The pinned read-only helper checks original task ownership and queries systemd ActiveState. The helper requires root and rejects mismatched ownership. The local consumer requires a successful complete zero-exit result and exact stdout before continuing; this is not a live ownership result.");

        var source = sources.get(application);
        if (source == null || !server.equals(source.get("server")) || !task.id().toString().equals(source.get("task"))
            || !layout.digest().equals(source.get("remoteLayoutSha256")))
            throw new IllegalArgumentException("No original archive association for candidate verification");
        long bytes = ((Number) source.get("archiveBytes")).longValue();
        String hash = source.get("archiveSha256").toString();
        boolean original = new DeploymentRemoteWorkspace(application, hash, layout).candidateId().equals(candidate);
        if (!original && reviewedOperations.stream().noneMatch(id ->
            new DeploymentRemoteWorkspace(application, hash, Optional.of(id), layout).candidateId().equals(candidate)))
            throw new AlternateCandidate();
        return Map.of("task", task.id().toString(), "application", application, "candidate", candidate,
            "helperStdoutPrefix", DeploymentCandidateChannel.OWNED_IDLE_STDOUT,
            "expectedStdout", DeploymentCandidateChannel.archiveVerificationOutput(bytes, hash),
            "archiveBytes", bytes, "archiveSha256", hash, "sourceManifestSha256", source.get("sourceManifestSha256"),
            "meaning", "The guarded remote command observes ownership, size and SHA-256. The original product consumer enforces full stdout equality (including root owner and inactive build), successful zero exit and no truncation before building. Any mismatch retains UNKNOWN. The expected values come from the captured compressed archive. This is the comparison contract; no remote bytes or ownership have yet been verified by this command.");
    }
}
