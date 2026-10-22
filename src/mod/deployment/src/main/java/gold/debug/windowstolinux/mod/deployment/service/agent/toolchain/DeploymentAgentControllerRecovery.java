package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildBudget;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildCommandChannel;
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;

/** Observes and settles only original controller effects; never runs a preparation worker or a build. */
public final class DeploymentAgentControllerRecovery {
    private DeploymentAgentControllerRecovery() { }

    public static boolean reconcile(ModContext context, TaskContext task, DeploymentAgentActionLog.Snapshot saved,
                                    DeploymentAgentActionLog journal, DeploymentRemoteLayout layout, String revision) throws Exception {
        var events = journal.events(task);
        var rows = DeploymentAgentToolchainProgramState.snapshot(context.tasks(), task, saved.identity().server(), layout, events);
        var server = new ServerId(saved.identity().server());
        var state = new DeploymentAgentToolchainProgramState(context, task, server, layout);
        for (var row : rows) if (!Boolean.TRUE.equals(row.get("released")) && Boolean.TRUE.equals(row.get("settled")))
            state.closeSettled(row.get("action").toString());
        rows = DeploymentAgentToolchainProgramState.snapshot(context.tasks(), task, saved.identity().server(), layout, journal.events(task));
        var pending = rows.stream().filter(row -> !Boolean.TRUE.equals(row.get("released"))).toList();
        if (pending.isEmpty()) return true;
        for (var row : pending) {
            if (Boolean.TRUE.equals(row.get("missing"))) return false;
            var payload = (JsonNode) row.get("payload");
            if (payload.has("trackedRequestRef")) DeploymentAgentToolchainProgramState.recipe(events, row.get("action").toString(), payload);
            else if (payload.has("storeFrame")) DeploymentAgentToolchainProgramState.validateStoreFrame(payload);
            else return false;
        }
        var reviewed = gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.scope(context,
            task, saved.identity().approvalMode(), saved.identity().rememberAuthorization(), () -> revision,
            gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCurrentCommandEvidence.NONE);
        try (var permission = gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization.recover(
                context, task, server, revision, saved.identity().approvalMode(), saved.identity().rememberAuthorization(),
                "original=" + task.id() + "\npurpose=observe-original-controller-and-owned-quiescent-cleanup-only\nno-worker-or-original-mutation-replay");
             var session = context.linux().connect(server, task)) {
            if (!saved.identity().targetDigest().equals(DeploymentAgentInputs.targetDigest(server, session))) return false;
            String fingerprint = DeploymentAgentToolchainTracked.hash(session.facts().fingerprint().getBytes(StandardCharsets.UTF_8));
            var channel = new DeploymentBuildCommandChannel(session, reviewed.interaction(), server, task, revision,
                new DeploymentBuildBudget(600), DeploymentSendGuard.NONE, layout);
            for (var row : pending) {
                String original = row.get("action").toString(); var payload = (JsonNode) row.get("payload");
                if (payload.has("trackedRequestRef")) {
                    var recipe = DeploymentAgentToolchainProgramState.recipe(journal.events(task), original, payload);
                    if (!recipe.fingerprintSha256().equals(fingerprint)) return false;
                    if (!Boolean.TRUE.equals(row.get("dispatched")) && payload.path("state").asText().equals("INTENT")) {
                        state.settleNotSent(original); continue;
                    }
                    var observed = tracked(channel, state, original, recipe, "observe-tracked");
                    if (observed == null) return false;
                    if (!observed.settled()) {
                        if (!cleanupAllowed(observed) || DeploymentAgentToolchainProgramState.recoveryEvents(journal.events(task), original).stream()
                            .anyMatch(value -> value.path("verb").asText().equals("cleanup-tracked"))) return false;
                        if (tracked(channel, state, original, recipe, "cleanup-tracked") == null) return false;
                        observed = tracked(channel, state, original, recipe, "observe-tracked");
                        if (observed == null || !observed.settled()) return false;
                    }
                    state.settle(original, observed);
                } else {
                    var frame = payload.path("storeFrame");
                    if (!frame.path("fingerprintSha256").asText().equals(fingerprint)) return false;
                    if (!Boolean.TRUE.equals(row.get("dispatched")) && payload.path("state").asText().equals("INTENT")) {
                        state.settleNotSent(original); continue;
                    }
                    var observation = readiness(channel, state, original, payload, layout);
                    if (observation == null || !observation.path("status").asText().equals("READY")) return false;
                    if (frame.path("phase").asText().equals("install") && (!observation.path("installTask").equals(frame.path("task"))
                        || !observation.path("installOperation").equals(frame.path("operation")))) return false;
                    state.settleStore(original, observation);
                }
            }
        }
        return !DeploymentAgentToolchainProgramState.hasUnconfirmed(context.tasks(), task, saved.identity().server(), layout, journal.events(task));
    }

    private static boolean cleanupAllowed(DeploymentAgentToolchainTracked.Observation observation) {
        var value = observation.value();
        if (!value.path("proofState").asText().equals("VERIFIED") || !observation.cleanupRequired()
            || observation.failureCode() != null && Set.of("REQUEST_INVALID", "IDENTITY_MISMATCH", "OPERATION_MISSING", "LEGACY_PROOF_MISSING", "LOCK_BUSY", "ACTIVE_CHILD", "RESOURCE_UNCONFIRMED", "RESOURCE_CHANGED", "INTERRUPTED").contains(observation.failureCode())) return false;
        if (!Set.of("ABSENT", "QUIESCENT").contains(value.path("resources").path("children").asText())) return false;
        for (String resource : List.of("unit", "cgroup"))
            if (!Set.of("ABSENT", "QUIESCENT", "OWNED_PRESENT").contains(value.path("resources").path(resource).asText())) return false;
        return java.util.stream.StreamSupport.stream(value.path("resources").spliterator(), false).noneMatch(state -> state.asText().equals("UNKNOWN"));
    }

    private static DeploymentAgentToolchainTracked.Observation tracked(DeploymentBuildCommandChannel channel,
            DeploymentAgentToolchainProgramState state, String original, DeploymentAgentToolchainTracked.Recipe recipe, String verb) throws Exception {
        String script = DeploymentAgentToolchainProgramStore.trackedCommand(verb, recipe);
        try (var request = new CommandRequest(script, recipe.bytes(), Duration.ofSeconds(verb.equals("observe-tracked") ? 60 : 180), 65536)) {
            String exact = gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(new ServerId(recipe.serverId()), "controller-recovery", request).exactAction();
            String id = state.recoveryIntent(original, verb, exact), hash = DeploymentAgentToolchainPrograms.sha256(exact);
            boolean[] dispatched = {false};
            OperationResult<CommandResult> actual;
            try {
                actual = channel.send(DeploymentAgentToolchainProgramStore.PREFIX + verb.replace("-tracked", "") + ".v1",
                    DeploymentAgentToolchainProgramStore.targets(new ServerId(recipe.serverId()), verb.replace("-tracked", ""), recipe.operationId(), recipe.operationId(), recipe.layout()),
                    script, recipe.bytes(), request.timeout(), 65536, () -> {
                        try { state.recoveryEvent(original, id, verb, "DISPATCHED", hash, null); }
                        catch (Exception failure) { throw new IllegalStateException("Recovery dispatch evidence unavailable", failure); }
                        dispatched[0] = true;
                    });
            } catch (ApiException failure) {
                actual = new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(failure.problem()));
            }
            state.recoveredTransport(id, actual);
            DeploymentAgentToolchainTracked.Observation observed = null;
            if (dispatched[0]) try { observed = DeploymentAgentToolchainTracked.receipt(actual, recipe, verb); }
            catch (IllegalArgumentException invalid) { /* actual transport remains retained */ }
            state.recoveryEvent(original, id, verb, !dispatched[0] ? "NOT_SENT" : observed == null ? "UNKNOWN" : "OBSERVED", hash,
                observed == null ? null : observed.value());
            return observed;
        }
    }

    private static JsonNode readiness(DeploymentBuildCommandChannel channel, DeploymentAgentToolchainProgramState state,
                                      String original, JsonNode payload, DeploymentRemoteLayout layout) throws Exception {
        var frame = payload.path("storeFrame"); String task = frame.path("task").asText(), op = frame.path("operation").asText();
        String script = DeploymentAgentToolchainProgramStore.command("readiness", task, op, List.of(), layout);
        try (var request = new CommandRequest(script, new byte[0], Duration.ofSeconds(30), 65536)) {
            String exact = gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(new ServerId(frame.path("server").asText()), "controller-recovery", request).exactAction();
            String id = state.recoveryIntent(original, "readiness", exact), hash = DeploymentAgentToolchainPrograms.sha256(exact);
            boolean[] dispatched = {false}; OperationResult<CommandResult> actual;
            try {
                actual = channel.send(DeploymentAgentToolchainProgramStore.PREFIX + "readiness.v1",
                    DeploymentAgentToolchainProgramStore.targets(new ServerId(frame.path("server").asText()), "readiness", op, op, layout),
                    script, new byte[0], request.timeout(), 65536, () -> {
                        try { state.recoveryEvent(original, id, "readiness", "DISPATCHED", hash, null); }
                        catch (Exception failure) { throw new IllegalStateException(failure); }
                        dispatched[0] = true;
                    });
            } catch (ApiException failure) { actual = new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(failure.problem())); }
            state.recoveredTransport(id, actual); JsonNode receipt = null;
            if (dispatched[0]) try { receipt = DeploymentAgentToolchainProgramStore.receipt(actual, task, op, layout, false); }
            catch (IllegalArgumentException invalid) { /* keep original installation and original unknown result */ }
            state.recoveryEvent(original, id, "readiness", !dispatched[0] ? "NOT_SENT" : receipt == null ? "UNKNOWN" : "OBSERVED", hash, receipt);
            return receipt;
        }
    }
}
