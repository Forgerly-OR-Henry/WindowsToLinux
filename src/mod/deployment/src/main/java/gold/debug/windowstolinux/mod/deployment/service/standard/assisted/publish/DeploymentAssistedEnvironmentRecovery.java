package gold.debug.windowstolinux.mod.deployment.service.standard.assisted.publish;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture;
import gold.debug.windowstolinux.mod.deployment.db.execution.*;
import gold.debug.windowstolinux.mod.deployment.linux.build.*;
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.cleanup.DeploymentPublishCleanupActionLog;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.graph.DeploymentStandardGraphPublishActionLog;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.policy.DeploymentPublishPolicyContext;

/** Reconciles only recorded original environment operations; never prepares, binds or builds. */
public final class DeploymentAssistedEnvironmentRecovery {
    private DeploymentAssistedEnvironmentRecovery() { }

    static boolean permits(ModContext context, TaskContext task, DeploymentExecutionRecord record,
                            DeploymentPublishCleanupActionLog journal) throws Exception {
        var store = new DeploymentExecutionStore(context.db());
        var intent = store.buildIntent(record).orElseThrow();
        var state = store.trackedToolchains(record, sha(intent.encode())).orElseThrow();
        var layout = DeploymentConfigCapture.originalTaskLayout(context.tasks(), task);
        return record.identity().taskId().equals(task.id()) && !record.businessCommitted() && !record.cleanupComplete()
            && state.identity().executionId().equals(record.identity().executionId().toString())
            && state.identity().unitId().equals(intent.unitId()) && state.identity().recoveryAttempt() == intent.attempt()
            && state.recipe().isPresent() && journal.actualBuild(intent)
            && state.operations().stream().allMatch(operation -> operation.request().taskId().equals(task.id().toString())
                && operation.request().serverId().equals(record.identity().server().id()) && operation.request().layout().equals(layout));
    }

    static boolean settled(ModContext context, TaskContext task, DeploymentExecutionRecord record,
                            DeploymentPublishCleanupActionLog journal) throws Exception {
        if (!permits(context, task, record, journal)) return false;
        var store = new DeploymentExecutionStore(context.db());
        var intent = store.buildIntent(record).orElseThrow();
        return journal.facts().path("environmentSettlementIntent").asText().equals(sha(intent.encode()))
            && store.trackedToolchains(record, sha(intent.encode())).orElseThrow().allSettled();
    }

    /** Returns the refreshed original formal record only after physical effects and pointers are confirmed. */
    public static Optional<DeploymentExecutionRecord> reconcile(ModContext context, TaskContext task,
                                                                DeploymentExecutionRecord record) throws Exception {
        var store = new DeploymentExecutionStore(context.db());
        var pending = store.buildIntent(record);
        if (pending.isEmpty()) return Optional.of(record);
        var intent = pending.orElseThrow();
        var graph = DeploymentStandardGraphPublishActionLog.reopen(context, task);
        DeploymentPublishCleanupActionLog journal = null;
        if (graph.isPresent()) {
            for (var index : graph.orElseThrow().indexes()) {
                if (index.unitId().equals(intent.unitId()))
                    journal = DeploymentPublishCleanupActionLog.reopen(context, task, index.key()).orElse(null);
            }
        } else journal = DeploymentPublishCleanupActionLog.reopen(context, task).orElse(null);
        if (journal == null) return Optional.empty();
        String environment = journal.facts().path("environmentRecovery").asText();
        if (environment.equals("SETTLED")) return settled(context, task, record, journal) ? Optional.of(record) : Optional.empty();
        if (!environment.equals("MAY_HAVE_RUN") && !environment.equals("VERIFIED")) return Optional.of(record);
        if (!permits(context, task, record, journal)) return Optional.empty();
        var state = store.trackedToolchains(record, sha(intent.encode())).orElseThrow();
        var identity = state.identity();
        var log = new DeploymentAssistedToolchainLog(store, task, record.identity().executionId(), identity);
        var tracked = new DeploymentTrackedToolchains.Session(identity, log);
        DeploymentTrackedToolchains.Request binding = null;
        try (var session = context.linux().connect(record.identity().server(), task)) {
            if (!sha(session.facts().fingerprint()).equals(identity.fingerprintSha256())) return Optional.empty();
            if (context instanceof DeploymentPublishPolicyContext guarded) guarded.ensureRecoveryHelper(session);
            var channel = new DeploymentBuildCommandChannel(session, context.interaction(), record.identity().server(), task,
                DeploymentAssistedBuildReviewCodec.decodeDescription(intent.nextPlan()).reviewedRevision(),
                new DeploymentBuildBudget(600), DeploymentSendGuard.NONE,
                DeploymentConfigCapture.originalTaskLayout(context.tasks(), task), DeploymentPreparationObserver.NONE, tracked);
            for (var operation : state.operations()) {
                var request = operation.request();
                String originalVerb = request.kind() == DeploymentTrackedToolchains.Kind.PREPARE ? "prepare-tracked" : "bind-tracked";
                if (!operation.dispatched(originalVerb)) continue;
                var observed = channel.observeTracked(request);
                if (observed.outcome() != TaskResult.SUCCEEDED || observed.value().isEmpty()) return Optional.empty();
                var proof = observed.value().orElseThrow();
                if (!settled(proof)) {
                    // A cleanup with an uncertain send is never replayed. Only the preceding observation may settle it.
                    if (operation.dispatched("cleanup-tracked") || proof.cleanupState() != DeploymentTrackedToolchains.CleanupState.REQUIRED)
                        return Optional.empty();
                    if (proof.failureCategory() != null && Set.of("operation_missing", "lock_busy", "ownership", "integrity", "interrupted")
                        .contains(proof.failureCategory())) return Optional.empty();
                    var cleaned = channel.cleanupTracked(request);
                    if (cleaned.outcome() != TaskResult.SUCCEEDED || cleaned.value().isEmpty()) return Optional.empty();
                    var after = channel.observeTracked(request);
                    if (after.outcome() != TaskResult.SUCCEEDED || after.value().isEmpty()) return Optional.empty();
                    proof = after.value().orElseThrow();
                }
                if (!settled(proof)) return Optional.empty();
                if (proof.bound()) {
                    if (binding != null) return Optional.empty();
                    binding = request;
                }
            }
        }
        if (binding == null) {
            if (!journal.recoverySettled(sha(intent.encode()))) return Optional.empty();
            return Optional.of(store.findForTask(task).orElseThrow());
        }
        var tools = binding.binding();
        String toolsSha = DeploymentToolchainBindingCodec.identity(tools);
        if (environment.equals("MAY_HAVE_RUN") && !journal.recoveryObserved(toolsSha)) return Optional.empty();
        if (environment.equals("VERIFIED") && !journal.facts().path("environmentTools").asText().equals(toolsSha)) return Optional.empty();
        var review = DeploymentAssistedPublishBuild.reviewed(context, intent, tools, state.recipe().orElseThrow().render(tools), task);
        if (review == null || !journal.reviewPending(intent) || graph.isPresent() && !graph.orElseThrow().reviewPending(intent))
            return Optional.empty();
        store.reconcileBuildReview(task, record.identity().executionId(), review, Instant.now());
        var refreshed = store.findForTask(task).orElseThrow();
        if (!refreshed.identity().reviewedRevision().equals(review.nextRoot()) || !journal.reviewConfirmed(review)
            || graph.isPresent() && !graph.orElseThrow().reviewConfirmed(review)) return Optional.empty();
        return Optional.of(refreshed);
    }

    private static boolean settled(DeploymentTrackedToolchains.Observation observation) {
        return observation.effectState() != DeploymentTrackedToolchains.EffectState.UNSETTLED
            && observation.cleanupState() != DeploymentTrackedToolchains.CleanupState.REQUIRED;
    }
    private static String sha(String value) { return DeploymentTrackedToolchains.sha256(value.getBytes(StandardCharsets.UTF_8)); }
}
