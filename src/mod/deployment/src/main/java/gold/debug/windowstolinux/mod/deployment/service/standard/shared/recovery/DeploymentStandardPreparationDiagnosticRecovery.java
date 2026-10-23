package gold.debug.windowstolinux.mod.deployment.service.standard.shared.recovery;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionStore;
import gold.debug.windowstolinux.mod.deployment.linux.build.*;
import gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentDatabasePlanActionLog;
import gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentNativeDatabaseActionLog;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.cleanup.DeploymentPublishCleanupActionLog;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.graph.DeploymentStandardGraphPublishActionLog;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.policy.DeploymentPublishPolicyFacts;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.policy.DeploymentPublishPolicyContext;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.source.DeploymentStandardSetupActionLog;

/** Reads original evidence and guards optional settlement of a complete single preparation as an interrupted task. */
public final class DeploymentStandardPreparationDiagnosticRecovery {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String SELECTION_TITLE = "Read original toolchain diagnostic";
    public static final String SELECTION_PROMPT = "Enter the original preparation operation UUID. This only selects a retained diagnostic attachment; it does not retry preparation, prove process termination or settle this task. Leave blank to keep the task unknown.";
    public static final String CONCLUSION_TITLE = "Conclude original preparation interruption";
    public static final String CONCLUSION_CONFIRMATION = "CONFIRM_FAILED";

    public static String conclusionPrompt(DeploymentToolchainDiagnostics.Request original) {
        return "Request guarded cleanup of this original single preparation and conclude its interrupted task as FAILED. This does not classify deployment as successful.\nTask="
            + original.taskId() + "\nOperation=" + original.operationId() + "\nOriginalRequestSHA256=" + original.sha256()
            + "\nPreparationStatus=SUCCEEDED\nType " + CONCLUSION_CONFIRMATION + "; leave blank to retain UNKNOWN. The host must independently verify the complete original checkpoint set and actual resource release.";
    }

    private DeploymentStandardPreparationDiagnosticRecovery() { }

    /** The absent diagnostic index makes this whole narrow interrupted-assisted branch conservative, including blank input. */
    static Optional<OperationResult<Void>> reconcileIfEligible(ModContext context, TaskContext task) throws Exception {
        if (new DeploymentExecutionStore(context.db()).findForTask(task).isPresent()) return Optional.empty();
        var marker = DeploymentStandardSetupActionLog.find(context.tasks(), task);
        if (marker.isEmpty() || marker.orElseThrow().wasCancelled()
            || context.tasks().findCheckpoint(task, DeploymentStandardSetupActionLog.KEY).orElseThrow().snapshot().released())
            return Optional.empty();
        var policy = DeploymentPublishPolicyFacts.find(context, task).orElseThrow().facts();
        if (!policy.executionMode().equals("ASSISTED")) return Optional.empty();
        if (DeploymentNativeDatabaseActionLog.find(context.tasks(), task).isPresent()
            || DeploymentDatabasePlanActionLog.find(context.tasks(), task).isPresent()
            || context.tasks().findCheckpoint(task, DeploymentStandardGraphPublishActionLog.KEY).isPresent()
            || context.tasks().findCheckpoint(task, DeploymentPublishCleanupActionLog.KEY).isPresent()) return Optional.empty();

        var details = new LinkedHashMap<String, String>();
        details.put("originalTask", task.id().toString());
        details.put("meaning", "A diagnostic read is independent evidence. It does not conclude original preparation or prove that its processes exited.");
        try {
            var selected = context.interaction().requestText(SELECTION_TITLE, SELECTION_PROMPT, false, task);
            details.put("diagnosticSelectionOutcome", selected.outcome().name());
            if (selected.outcome() != TaskResult.SUCCEEDED || selected.value().isEmpty()) return Optional.of(unknown(details));
            String value = selected.value().orElseThrow().strip();
            if (value.length() != 36) return Optional.of(unknown(details));
            UUID operation = UUID.fromString(value);
            if (!operation.toString().equals(value)) return Optional.of(unknown(details));
            details.put("diagnosticOperation", operation.toString());
            var retained = DeploymentToolchainDiagnostics.retained(context.tasks(), task, operation);
            if (retained.isEmpty()) {
                details.put("diagnosticObservation", "ORIGINAL_REQUEST_ABSENT");
                return Optional.of(unknown(details));
            }
            var original = retained.orElseThrow();
            var layout = DeploymentConfigCapture.originalTaskLayout(context.tasks(), task);
            if (!original.serverId().equals(policy.target().server().id()) || !original.layout().equals(layout)
                || policy.actualFingerprint().isEmpty()
                || !original.fingerprintSha256().equals(DeploymentTrackedToolchains.sha256(
                    policy.actualFingerprint().orElseThrow().getBytes(StandardCharsets.UTF_8)))) {
                details.put("diagnosticObservation", "ORIGINAL_BINDING_DIFFERS");
                return Optional.of(unknown(details));
            }
            details.put("diagnosticRequestSha256", original.sha256());
            details.put("diagnosticPlanRevision", original.planRevision());
            details.put("diagnosticSessionCloseConfirmed", "false");
            boolean succeeded = false;
            try (RemoteSession session = context.linux().connect(new ServerId(original.serverId()), task)) {
                var channel = new DeploymentBuildCommandChannel(session, context.interaction(), new ServerId(original.serverId()),
                    task, original.planRevision(), new DeploymentBuildBudget(60), layout);
                var observed = channel.toolchainDiagnostic(original);
                details.put("diagnosticReadOutcome", observed.outcome().name());
                observed.value().ifPresent(attachment -> retainAttachment(details, attachment));
                observed.problem().ifPresent(problem -> details.put("diagnosticReadProblem", problem.eventCode()));
                if (observed.outcome() == TaskResult.SUCCEEDED && observed.value().isPresent()) {
                    var attachment = JSON.readTree(observed.value().orElseThrow());
                    succeeded = attachment.path("preparationStatus").asText().equals("SUCCEEDED")
                        && attachment.path("actualExitCode").isIntegralNumber() && attachment.path("actualExitCode").asInt() == 0
                        && !attachment.path("timedOut").asBoolean() && !attachment.path("cancelled").asBoolean();
                }
            }
            details.put("diagnosticSessionCloseConfirmed", "true");
            if (succeeded) {
                var confirmed = context.interaction().requestText(CONCLUSION_TITLE, conclusionPrompt(original), false, task);
                details.put("diagnosticConclusionSelectionOutcome", confirmed.outcome().name());
                if (confirmed.outcome() == TaskResult.SUCCEEDED && confirmed.value().filter(CONCLUSION_CONFIRMATION::equals).isPresent()) {
                    if (DeploymentPublishPolicyContext.releaseSingleDiagnosticPreparation(context, task,
                        DeploymentToolchainDiagnostics.checkpointKey(original.operationId()))) {
                        details.put("diagnosticConclusion", "SINGLE_ORIGINAL_PREPARATION_VERIFIED");
                        details.put("meaning", "The single original preparation completed and all task resources were settled; publication never began. The interrupted task is FAILED, not a successful deployment.");
                        return Optional.of(new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.IO_FAILURE,
                            "DEPLOYMENT_STATIC_PREPARATION_INTERRUPTED", "Original preparation completed before an interrupted publication workflow",
                            details, Optional.empty()))));
                    }
                    details.put("diagnosticConclusion", "COMPLETE_SCOPE_OR_RESOURCE_RELEASE_UNCONFIRMED");
                }
            }
        } catch (ApiException unavailable) {
            details.put("diagnosticObservation", unavailable.problem().eventCode());
        } catch (IllegalArgumentException invalid) {
            details.put("diagnosticObservation", "ORIGINAL_DIAGNOSTIC_UNCONFIRMED");
        }
        return Optional.of(unknown(details));
    }

    /** Host task evidence is limited to 65,536 characters, including JSON's second layer of escaping. */
    private static void retainAttachment(Map<String, String> details, String attachment) {
        details.put("diagnosticAttachmentSha256", DeploymentTrackedToolchains.sha256(attachment.getBytes(StandardCharsets.UTF_8)));
        if (JSON.createObjectNode().put("toolchainDiagnostic", attachment).toString().length() <= 48000) {
            details.put("toolchainDiagnostic", attachment);
            details.put("diagnosticAttachmentExportTruncated", "false");
            return;
        }
        try {
            var excerpt = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(attachment);
            for (String key : java.util.List.of("stdout", "stderr", "failureCategory")) {
                var value = excerpt.path(key);
                if (value.isTextual() && value.textValue().length() > 1024) {
                    excerpt.put(key, value.textValue().substring(value.textValue().length() - 1024));
                    excerpt.put("export" + Character.toUpperCase(key.charAt(0)) + key.substring(1) + "Truncated", true);
                }
            }
            details.put("toolchainDiagnosticExcerpt", excerpt.toString());
            details.put("diagnosticAttachmentExportTruncated", "true");
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("Validated diagnostic attachment cannot be exported", invalid);
        }
    }

    private static OperationResult<Void> unknown(Map<String, String> details) {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
            "DEPLOYMENT_PREPARATION_DIAGNOSTIC_OBSERVATION", "Original preparation remains unknown after an independent diagnostic observation",
            details, Optional.empty())));
    }
}
