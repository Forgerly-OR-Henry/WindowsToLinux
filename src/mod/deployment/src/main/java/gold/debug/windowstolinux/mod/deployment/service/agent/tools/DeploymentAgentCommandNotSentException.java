package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import java.util.Objects;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;

/** An actual agent-run approval failed before its dispatcher called execute; this is never permission or retry feedback. */
public final class DeploymentAgentCommandNotSentException extends Exception {
    public static final String DETAIL = "REVIEW_STOPPED_NOT_SENT";

    private final UUID task;

    private final String candidate;

    private final String sourceRevision;

    private final String scriptSha256;

    private final long seconds;

    private final String requestSha256;

    private final Exception originalFailure;

    /** Only the owning Agent channel can record its actual pre-execute boundary. */
    DeploymentAgentCommandNotSentException(Exception original, UUID task, String candidate, String sourceRevision,
                                           String scriptSha256, long seconds, String requestSha256) {
        super(original.getMessage(), original);
        if (!(original instanceof ApiException || original instanceof DeploymentAiJson.CapabilityOutcomeException))
            throw new IllegalArgumentException("Unsupported Agent review failure");
        this.originalFailure = original;
        this.task = Objects.requireNonNull(task);
        this.candidate = Objects.requireNonNull(candidate);
        this.sourceRevision = Objects.requireNonNull(sourceRevision);
        this.scriptSha256 = Objects.requireNonNull(scriptSha256);
        this.seconds = seconds;
        this.requestSha256 = Objects.requireNonNull(requestSha256);
        if (!candidate.matches("[A-Za-z0-9._-]{1,128}") || !sourceRevision.matches("[0-9a-f]{64}")
            || !scriptSha256.matches("[0-9a-f]{64}") || !requestSha256.matches("[0-9a-f]{64}")
            || seconds < 1 || seconds > 7200)
            throw new IllegalArgumentException("Invalid Agent dispatcher boundary identity");
    }

    /** Returns the exact original classified failure, retaining outcome, Problem and cause. */
    public Exception originalFailure() {
        return originalFailure;
    }

    /** Matches the current single dispatch scope; command callers must also compare the exact script digest. */
    public boolean matches(UUID originalTask, String originalCandidate, String originalSource, long originalSeconds) {
        return task.equals(originalTask) && candidate.equals(originalCandidate) && sourceRevision.equals(originalSource)
            && seconds == originalSeconds;
    }

    public String scriptSha256() {
        return scriptSha256;
    }

    /** Bounded public identities and digests only, without command bodies, review text or any authorization. */
    public String dispatcherProof() {
        return "agent-run-review-stop-v1 task=" + task + " candidate=" + candidate + " sourceSha256=" + sourceRevision
            + " scriptSha256=" + scriptSha256 + " seconds=" + seconds + " requestSha256=" + requestSha256;
    }
}
