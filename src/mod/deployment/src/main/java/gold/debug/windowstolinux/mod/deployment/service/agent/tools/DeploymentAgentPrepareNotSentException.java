package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;

/** Exact first-install review failure before dispatch; its proof and native refusal data convey no authority. */
public final class DeploymentAgentPrepareNotSentException extends Exception {
    public static final String DETAIL = "INSTALL_REVIEW_STOPPED_NOT_SENT";

    private final TaskContext task;

    private final String component;

    private final String sourceRevision;

    private final String server;

    private final String configuration;

    private final String installerSha256;

    private final String requestSha256;

    private final Exception originalFailure;

    DeploymentAgentPrepareNotSentException(Exception original, TaskContext task, String component, String sourceRevision,
                                           ServerId server, String configuration, String installerSha256, String requestSha256) {
        super(original.getMessage(), original);
        if (!(original instanceof ApiException || original instanceof DeploymentAiJson.CapabilityOutcomeException))
            throw new IllegalArgumentException("Unsupported Agent install review failure");
        this.originalFailure = original;
        this.task = Objects.requireNonNull(task);
        this.component = Objects.requireNonNull(component);
        this.sourceRevision = Objects.requireNonNull(sourceRevision);
        this.server = Objects.requireNonNull(server).id();
        this.configuration = Objects.requireNonNull(configuration);
        this.installerSha256 = Objects.requireNonNull(installerSha256);
        this.requestSha256 = Objects.requireNonNull(requestSha256);
        if (!component.matches("[a-z0-9][a-z0-9-]{0,62}") || !sourceRevision.matches("[0-9a-f]{64}")
            || !configuration.matches("[0-9a-f]{64}") || !installerSha256.matches("[0-9a-f]{64}")
            || !requestSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid Agent installer review boundary identity");
    }

    public Exception originalFailure() {
        return originalFailure;
    }

    public boolean matches(TaskContext originalTask, String originalComponent, String originalSource) {
        return task == originalTask && component.equals(originalComponent) && sourceRevision.equals(originalSource);
    }

    /** Only an original native verdict at this captured unsent boundary can become refusal feedback. */
    public Optional<ApiException> nativeReviewDenial(TaskContext originalTask) {
        if (task != originalTask || !(originalFailure instanceof ApiException denied)
            || denied.problem().code() != gold.debug.windowstolinux.appapi.task.result.ProblemCode.ACCESS_DENIED
            || !denied.problem().eventCode().equals("DEPLOYMENT_COMMAND_REVIEW_DENIED")
            || !task.id().toString().equals(denied.problem().context().get("task")))
            return Optional.empty();
        var data = denied.problem().context();
        String binding = data.get("reviewBinding");
        return binding != null && binding.matches("[0-9a-f]{64}")
            && java.util.Set.of("OPEN", "CLOSED").contains(data.getOrDefault("discussionStatus", ""))
            ? Optional.of(denied) : Optional.empty();
    }

    public String dispatcherProof() {
        return prefix(task.id(), component, sourceRevision, server, configuration) + " installerSha256=" + installerSha256
            + " requestSha256=" + requestSha256;
    }

    /** Validates only persisted public identity; this data conveys no execution authority. */
    public static boolean matchesProof(String proof, UUID originalTask, String originalComponent, String originalSource,
                                       String originalServer, String originalConfiguration) {
        String prefix = prefix(originalTask, originalComponent, originalSource, originalServer, originalConfiguration)
            + " installerSha256=";
        return proof.startsWith(prefix) && proof.substring(prefix.length()).matches("[0-9a-f]{64} requestSha256=[0-9a-f]{64}");
    }

    private static String prefix(UUID task, String component, String source, String server, String configuration) {
        return "agent-install-review-stop-v1 task=" + task + " component=" + component + " sourceSha256=" + source
            + " serverSha256=" + hash(server) + " revision=" + configuration;
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
}
