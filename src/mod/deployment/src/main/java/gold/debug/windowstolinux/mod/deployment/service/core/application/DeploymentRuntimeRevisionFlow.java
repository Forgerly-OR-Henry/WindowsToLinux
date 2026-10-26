package gold.debug.windowstolinux.mod.deployment.service.core.application;

import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.api.*;
import gold.debug.windowstolinux.mod.deployment.db.execution.*;
import gold.debug.windowstolinux.mod.deployment.db.publish.*;
import gold.debug.windowstolinux.mod.deployment.db.release.*;
import gold.debug.windowstolinux.mod.deployment.db.reference.*;
import gold.debug.windowstolinux.mod.deployment.db.resource.*;
import gold.debug.windowstolinux.mod.deployment.linux.runtime.DeploymentWorkloadArguments;
import gold.debug.windowstolinux.mod.deployment.service.core.handoff.DeploymentHandoffChannel;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.*;
import gold.debug.windowstolinux.mod.deployment.service.core.setup.*;

/** Native settings revisions retain the exact source release instead of rediscovering a local project. */
final class DeploymentRuntimeRevisionFlow {
    private final ModContext context;
    private final DeploymentManagementActionLog journal;
    DeploymentRuntimeRevisionFlow(ModContext context) { this.context = context; journal = new DeploymentManagementActionLog(context); }

    DeploymentRuntimeSettings.Form form(DeploymentApplicationSnapshot selected, TaskContext task) throws Exception {
        var data = current(selected, task);
        var host = context.servers().get(selected.application().server(), task).host();
        return new DeploymentRuntimeSettings.Form(selected, data.graph().components().stream()
            .map(p -> DeploymentRuntimeSettings.values(p, host)).toList());
    }

    OperationResult<DeploymentRuntimeSettings.Review> review(DeploymentRuntimeSettings.Form form,
                                                             DeploymentRuntimeSettings.Values edits, TaskContext task) throws Exception {
        var data = current(form.selected(), task);
        var source = proposal(data, task);
        var operationId = UUID.randomUUID();
        var revisions = new LinkedHashMap<String, Long>();
        var configs = new DeploymentConfigStore(context.db());
        for (var unit : source.units()) revisions.put(unit.unit().id(), configs.nextRevision(task, unit.unit().id()));
        var target = DeploymentRuntimeSettings.patch(source, edits, revisions, operationId);
        var changed = target.graph().components().stream().filter(p -> p.componentId().equals(edits.component())).findFirst().orElseThrow();
        var effective = new DeploymentRuntimeSettings.Values(edits.component(), changed.runtime().workload().endpoints().isEmpty() ? ""
            : Integer.toString(changed.runtime().workload().endpoints().getFirst().hostPort()), edits.accessUrl().trim(),
            changed.runtime().workload().command().entrypoint(),
            gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentCommandText.format(changed.runtime().workload().command().arguments()));
        var layout = data.layout();
        gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.remoteLayout(context.tasks(), task, layout);
        var server = form.selected().application().server();
        try (var session = context.linux().connect(server, task)) {
            if (session.facts().fingerprint().isBlank()) return failed("DEPLOYMENT_SETTINGS_TARGET_FINGERPRINT_MISSING");
            var reviewFacts = DeploymentManagementActionLog.facts().put("runtimeSettingsKind", "REVIEW").put("operationId", operationId.toString())
                .put("runtimeSettingsVersion", 2)
                .put("sourceTemplate", DeploymentPublishStore.archiveTemplate(source)).put("targetTemplate", DeploymentPublishStore.archiveTemplate(target))
                .put("fingerprint", session.facts().fingerprint()).put("targetIdentity", targetIdentity(server, task));
            var retainedReview = journal.begin(form.selected().application().id(), server, "HISTORY_SWITCH",
                form.selected().revisionSha256(), Optional.of(digest(target)), reviewFacts, task);
            var bootstrap = new DeploymentProtocolBootstrap(context.tasks(), task, server, session, context.interaction(), layout).ensure();
            if (bootstrap.outcome() != TaskResult.SUCCEEDED) return copy(bootstrap);
            var channel = new DeploymentHandoffChannel(session, context.interaction(), server, task, digest(target), layout);
            var components = new ArrayList<DeploymentRuntimeSettings.ComponentReview>();
            for (var component : target.graph().components()) {
                var old = unit(source, component.unit().id());
                var commit = unit(target, component.unit().id());
                if (old.release().sha256().equals(commit.release().sha256())) continue;
                String port = component.componentId().equals(effective.component()) && !effective.port().isBlank() ? effective.port() : "0";
                var args = List.of(component.unit().id(), old.release().sha256(), component.unit().ownershipManifestSha256(),
                    DeploymentWorkloadArguments.payload(component.reviewedRuntime()), commit.configuration().sha256(), port);
                var observed = channel.execute("runtime-settings-preview", args, new byte[0], Duration.ofMinutes(2), 65536);
                if (observed.outcome() != TaskResult.SUCCEEDED || observed.value().isEmpty()
                    || observed.value().orElseThrow().truncated() || !observed.value().orElseThrow().exitCode().equals(Optional.of(0)))
                    return unknown("DEPLOYMENT_SETTINGS_PREVIEW_UNCONFIRMED");
                var match = java.util.regex.Pattern.compile("BEFORE=([A-Za-z0-9+/=]*)\\nAFTER=([A-Za-z0-9+/=]*)\\nRECIPE=([0-9a-f]{64})\\n")
                    .matcher(observed.value().orElseThrow().stdout());
                if (!match.matches()) return unknown("DEPLOYMENT_SETTINGS_PREVIEW_INVALID");
                components.add(new DeploymentRuntimeSettings.ComponentReview(component.componentId(), component.unit().id(),
                    new String(Base64.getDecoder().decode(match.group(1)), StandardCharsets.UTF_8),
                    new String(Base64.getDecoder().decode(match.group(2)), StandardCharsets.UTF_8), match.group(3), port,
                    DeploymentRuntimeSettings.configurationChanges(old, commit, effective.component())));
            }
            var review = new DeploymentRuntimeSettings.Review(form, effective, source, target, components,
                session.facts().fingerprint(), targetIdentity(server, task), operationId);
            var reviewedFacts = DeploymentManagementActionLog.copy(retainedReview).put("prepared", true);
            reviewedFacts.set("runtimeSettingsComponents", componentFacts(review));
            journal.advance(retainedReview, "FINISHED", reviewedFacts, task);
            return success(review);
        }
    }

    OperationResult<String> apply(DeploymentRuntimeSettings.Review review, TaskContext task) throws Exception {
        var selected = review.form().selected();
        if (review.fingerprint().isBlank()) return failed("DEPLOYMENT_SETTINGS_TARGET_FINGERPRINT_MISSING");
        var data = current(selected, task);
        var actual = proposal(data, task);
        if (!snapshot(actual).equals(snapshot(review.source())) || !targetIdentity(selected.application().server(), task).equals(review.targetIdentity()))
            return failed("DEPLOYMENT_SETTINGS_REVIEW_CHANGED");
        var target = review.target();
        var revisions = new LinkedHashMap<String, Long>();
        target.units().forEach(c -> revisions.put(c.unit().id(), c.configuration().revision()));
        if (!snapshot(DeploymentRuntimeSettings.patch(actual, review.edited(), revisions, review.operationId())).equals(snapshot(target)))
            return failed("DEPLOYMENT_SETTINGS_PLAN_CHANGED");
        var layout = data.layout();
        gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.remoteLayout(context.tasks(), task, layout);
        String next = digest(target);
        var facts = DeploymentManagementActionLog.facts().put("runtimeSettingsKind", "APPLY").put("runtimeSettingsVersion", 2)
            .put("sourceTemplate", DeploymentPublishStore.archiveTemplate(actual))
            .put("targetTemplate", DeploymentPublishStore.archiveTemplate(target)).put("fingerprint", review.fingerprint())
            .put("targetIdentity", review.targetIdentity())
            .put("executionId", review.operationId().toString());
        facts.set("runtimeSettingsComponents", componentFacts(review));
        var parent = new DeploymentReleaseStore(context.db()).list(task, selected.application().id()).stream()
            .filter(r -> r.state() == DeploymentReleaseRecord.DeploymentReleaseState.ACTIVE).findFirst().orElseThrow();
        if (!parent.identity().sha256().equals(selected.revisionSha256())) return failed("DEPLOYMENT_SETTINGS_REVIEW_CHANGED");
        facts.put("sourceReleaseId", parent.identity().id().toString());
        var parts = facts.putObject("components");
        target.graph().components().forEach(p -> parts.putObject(p.componentId()).put("snapshotAttempted", false)
            .put("activateAttempted", false).put("finishAttempted", false).put("finished", false));
        var operation = journal.begin(selected.application().id(), selected.application().server(), "HISTORY_SWITCH",
            selected.revisionSha256(), Optional.of(next), facts, task);
        var intents = new LinkedHashSet<DeploymentRefIntent>();
        intents.add(DeploymentRefKey.application(selected.application().server(), selected.application().id()));
        var secrets = new DeploymentSecretStore(context.db());
        for (var unit : target.units()) {
            intents.add(DeploymentRefKey.unit(unit.unit().server(), unit.unit().id()));
            for (var secret : unit.secrets()) {
                var metadata = secrets.findRevision(task, secret).orElseThrow();
                intents.add(DeploymentRefKey.secretRevision(unit.unit().server(),
                    new gold.debug.windowstolinux.appapi.secret.model.SecretId(metadata.credentialId(), metadata.purpose().orElseThrow()), secret));
            }
        }
        var plan = new DeploymentPublishPlan(UUID.fromString(facts.path("executionId").asText()), Optional.empty(),
            selected.application().id(), selected.application().server(), DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC,
            next, List.copyOf(intents), Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        var retained = new DeploymentRetainedReleases(context);
        var flow = new DeploymentRefFlow(context);
        var result = flow.executeWithinTask(plan, original -> {
            new DeploymentExecutionStore(context.db()).recordPublicationTemplate(original, plan.executionId(), target, Instant.now());
            return retained.revise(operation, actual, target, original, true, false);
        }, task, Optional.of(selected.revisionSha256()), original -> retained.finishRevision(journal.find(original).orElseThrow(), actual, target, original));
        return result.outcome() == TaskResult.SUCCEEDED ? success(next) : copy(result);
    }

    OperationResult<Void> reconcile(DeploymentManagementActionLog.Entry operation, TaskContext task) throws Exception {
        var source = DeploymentPublishStore.archivedTemplate(operation.facts().path("sourceTemplate").asText(), operation.currentRevision());
        var target = DeploymentPublishStore.archivedTemplate(operation.facts().path("targetTemplate").asText(), operation.targetRevision().orElseThrow());
        if (!targetIdentity(operation.server(), task).equals(operation.facts().path("targetIdentity").asText()))
            return unknown("DEPLOYMENT_SETTINGS_TARGET_CHANGED");
        var executions = new DeploymentExecutionStore(context.db());
        var stored = executions.findForTask(task);
        if (stored.isEmpty()) return failed("DEPLOYMENT_SETTINGS_NOT_STARTED");
        var execution = stored.orElseThrow();
        var references = new DeploymentRefFlow(context);
        if (execution.phase().equals("PREPARED") && !execution.businessCommitted()
            && execution.outcome().filter(value -> value != TaskResult.UNKNOWN).isEmpty())
            return references.settleWithinTask(task);
        if (!execution.businessCommitted() && operation.phase().equals("REVIEWED")
            && execution.outcome().filter(value -> value != TaskResult.UNKNOWN).isEmpty()
            && !operation.facts().path("components").findValues("deriveAttempted").stream().anyMatch(com.fasterxml.jackson.databind.JsonNode::asBoolean)) {
            String event = "DEPLOYMENT_SETTINGS_INTERRUPTED_BEFORE_DERIVE";
            journal.advance(operation, "FINISHED", DeploymentManagementActionLog.copy(operation)
                .put("terminalOutcome", "FAILED").put("failureEvent", event), task);
            executions.recordUncommittedOutcome(task, execution.identity().executionId(), TaskResult.FAILED,
                new Problem(ProblemCode.INCOMPATIBLE, event, "No runtime derivation or activation was dispatched", Map.of(), Optional.empty()), Instant.now());
            return references.settleWithinTask(task);
        }
        if (!execution.businessCommitted() && operation.phase().equals("FINISHED")
            && operation.facts().path("terminalOutcome").asText().equals("FAILED")) {
            if (execution.outcome().filter(value -> value != TaskResult.UNKNOWN).isEmpty()) {
                String event = operation.facts().path("failureEvent").asText();
                if (!event.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")) return unknown("DEPLOYMENT_SETTINGS_FAILURE_EVIDENCE_INVALID");
                executions.recordUncommittedOutcome(task, execution.identity().executionId(), TaskResult.FAILED,
                    new Problem(ProblemCode.INCOMPATIBLE, event, "Original settings revision was independently confirmed failed", Map.of(), Optional.empty()), Instant.now());
            }
            return references.settleWithinTask(task);
        }
        if (execution.outcome().isPresent() && execution.outcome().orElseThrow() != TaskResult.UNKNOWN && !execution.businessCommitted())
            return references.settleWithinTask(task);
        var retained = new DeploymentRetainedReleases(context);
        var observed = retained.revise(operation, source, target, task, false, execution.businessCommitted());
        if (observed.outcome() == TaskResult.SUCCEEDED) {
            if (!execution.businessCommitted()) executions.commitApplication(task, execution.identity().executionId(), target.graph(), target.units(), Instant.now(), Optional.of(operation.currentRevision()));
            var finished = retained.finishRevision(journal.find(task).orElseThrow(), source, target, task);
            if (finished.outcome() != TaskResult.SUCCEEDED) return copy(finished);
        } else if (observed.outcome() == TaskResult.FAILED && !execution.businessCommitted()) {
            executions.recordUncommittedOutcome(task, execution.identity().executionId(), TaskResult.FAILED, observed.problem().orElseThrow(), Instant.now());
        } else return copy(observed);
        return references.settleWithinTask(task);
    }

    private DeploymentApplicationReader.ApplicationFacts current(DeploymentApplicationSnapshot selected, TaskContext task) throws Exception {
        var data = new DeploymentApplicationReader(context.db()).find(selected.application(), task);
        if (!data.snapshot().revisionSha256().equals(selected.revisionSha256())) throw new IllegalArgumentException("Reviewed release changed");
        if (data.graph().components().stream().anyMatch(p -> p.reviewedRuntime() instanceof DeploymentRuntimeDefinition.Container))
            throw new IllegalArgumentException("Container settings revision is not implemented; the runtime was not changed");
        return data;
    }

    private static DeploymentUnitCommit unit(DeploymentPublishProposal proposal, String id) {
        return proposal.units().stream().filter(c -> c.unit().id().equals(id)).findFirst().orElseThrow();
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode componentFacts(DeploymentRuntimeSettings.Review review) {
        var result = DeploymentManagementActionLog.facts();
        result.removeAll();
        var expected = new HashSet<String>();
        for (var part : review.target().graph().components())
            if (!unit(review.source(), part.unit().id()).release().sha256().equals(unit(review.target(), part.unit().id()).release().sha256()))
                expected.add(part.componentId());
        for (var component : review.components()) {
            if (!expected.remove(component.component()) || !component.recipeSha256().matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Settings review component coverage differs");
            var part = review.target().graph().components().stream().filter(p -> p.componentId().equals(component.component())).findFirst().orElseThrow();
            var old = unit(review.source(), part.unit().id());
            var target = unit(review.target(), part.unit().id());
            String expectedPort = component.component().equals(review.edited().component()) && !review.edited().port().isBlank() ? review.edited().port() : "0";
            if (!component.unitId().equals(part.unit().id()) || !component.port().equals(expectedPort)
                || !component.configurationChanges().equals(DeploymentRuntimeSettings.configurationChanges(old, target, review.edited().component())))
                throw new IllegalArgumentException("Settings review component identity differs");
            var facts = result.putObject(component.component()).put("unitId", component.unitId())
                .put("sourceReleaseSha256", old.release().sha256()).put("targetReleaseSha256", target.release().sha256())
                .put("configurationSha256", target.configuration().sha256()).put("port", component.port())
                .put("recipeSha256", component.recipeSha256()).put("beforeCommand", component.beforeCommand()).put("afterCommand", component.afterCommand());
            var changes = facts.putArray("configurationChanges");
            for (var change : component.configurationChanges()) changes.addObject().put("key", change.key())
                .put("beforeValue", change.beforeValue()).put("afterValue", change.afterValue()).put("sourceComponent", change.sourceComponent());
        }
        if (!expected.isEmpty() || review.components().isEmpty()) throw new IllegalArgumentException("Settings review misses changed components");
        return result;
    }

    private DeploymentPublishProposal proposal(DeploymentApplicationReader.ApplicationFacts data, TaskContext task) throws Exception {
        var configs = new DeploymentConfigStore(context.db());
        var commits = new ArrayList<DeploymentUnitCommit>();
        for (var part : data.graph().components()) {
            var binding = data.release().bindings().stream().filter(b -> b.unitId().equals(part.unit().id())).findFirst().orElseThrow();
            var config = configs.find(task, part.unit().id(), binding.configurationRevision()).orElseThrow();
            if (!config.sha256().equals(binding.configurationSha256())) throw new IllegalArgumentException("Original configuration changed");
            var release = new DeploymentPublishStore(context.db()).findRelease(task, part.unit().id()).orElseThrow();
            if (!release.sha256().equals(binding.releaseSha256())) throw new IllegalArgumentException("Original unit release changed");
            commits.add(new DeploymentUnitCommit(part.unit(), part.runtime(), release, config, binding.secrets()));
        }
        return new DeploymentPublishProposal(data.graph(), commits);
    }

    private String targetIdentity(gold.debug.windowstolinux.appapi.server.model.ServerId server, TaskContext task) throws ApiException {
        var target = context.servers().get(server, task);
        return target.ref().id() + "\n" + target.username() + "\n" + target.host() + "\n" + target.port();
    }
    private static DeploymentReleaseSnapshot snapshot(DeploymentPublishProposal p) { return DeploymentReleaseSnapshot.capture(p.graph(), p.units()); }
    private static String digest(DeploymentPublishProposal p) throws java.sql.SQLException {
        var snapshot = snapshot(p); return DeploymentReleaseCodec.digest(DeploymentReleaseCodec.runtime(snapshot), DeploymentReleaseCodec.health(snapshot));
    }
    private static <T> OperationResult<T> success(T value) { return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty()); }
    private static <T> OperationResult<T> copy(OperationResult<?> result) { return new OperationResult<>(result.outcome(), Optional.empty(), result.problem()); }
    private static <T> OperationResult<T> failed(String event) { return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.INCOMPATIBLE, event, event, Map.of(), Optional.empty()))); }
    private static <T> OperationResult<T> unknown(String event) { return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN, event, event, Map.of(), Optional.empty()))); }
}
