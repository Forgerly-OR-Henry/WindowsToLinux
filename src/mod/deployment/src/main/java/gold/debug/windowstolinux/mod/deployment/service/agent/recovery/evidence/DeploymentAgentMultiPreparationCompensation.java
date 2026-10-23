package gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument;
import gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentWorkspaceBinding;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPartialPreparationProbe;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentProjectChannel;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentManagedUnitResolver;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;

/** One bounded original candidate set; old single-preparation proofs retain their exact meaning. */
public final class DeploymentAgentMultiPreparationCompensation {
    public static final String EVENT = "PREPARE_SET_COMPENSATED";

    public static final String KEY = "agent-multiprepare-compensation.v1";

    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final Set<String> TYPES = Set.of("REMEMBER_AUTHORIZATION", "REMOTE_TARGET", "REMOTE_LAYOUT", "SOURCE", "SUCCESSOR_BINDING",
        "TARGET_PROFILE", "AGENT_QUERY", "AGENT_ASSESSMENT", "AGENT_OBSERVATION", "DECISION", "PLAN", "PLAN_APPLICATION", "STATE", "WORKSPACE_QUOTA",
        "PREPARE_EXECUTING", "PREPARE_RESULT", "HELPER_READINESS_OBSERVED", "CANDIDATE_QUOTA", "CANDIDATE", "CURRENT_SOURCE",
        "COMMAND_EXECUTING", "COMMAND_RESULT", "CAPABILITY_EXECUTING", "CAPABILITY_RESULT", "SEAL_EXECUTING", "SEAL_RESULT", "SEALED_ARTIFACT",
        "AGENT_DATABASE_INDEX", "AGENT_SECRET_INDEX", "RECOVERY_REVIEW_BOUND", "RECOVERY_REVIEW_REBOUND");

    private static final Set<String> ROW = Set.of("candidate", "phase", "before", "beforeRequest", "beforeResult", "cleanupRequest", "afterRequest", "afterResult");

    private DeploymentAgentMultiPreparationCompensation() {
    }

    public record Member(DeploymentAgentActionLog.Event preparation, DeploymentAgentActionLog.Event plan,
                         DeploymentAgentActionLog.Event quota, DeploymentRemoteWorkspace workspace, boolean pending) {
    }

    public record Captured(UUID task, DeploymentAgentActionLog.Snapshot saved, DeploymentRemoteLayout layout,
                           List<DeploymentAgentActionLog.Event> events, List<Member> members) {
        public Captured {
            events = List.copyOf(events);
            members = List.copyOf(members);
        }

        public Member pending() {
            return members.stream().filter(Member::pending).findFirst().orElseThrow();
        }

        public String originSha256() {
            return DeploymentAgentPreparationAbsence.digest(events.stream()
                .filter(e -> !e.type().startsWith("RECOVERY_REVIEW_")).toList());
        }

        public DeploymentAgentPreparationCompensation.Captured view(Member m) {
            return new DeploymentAgentPreparationCompensation.Captured(task, saved, layout, m.preparation(), m.workspace(),
                one(events, "SOURCE").detail(), m.plan().detail(), one(events, "TARGET_PROFILE").detail(),
                one(events, "REMOTE_TARGET").detail(), oneApplication(events), events.stream()
                .filter(e -> !e.type().startsWith("RECOVERY_REVIEW_")).toList());
        }
    }

    /** Validates every original plan epoch; filtered quota views never replace the full retained prefix. */
    public static Optional<Captured> capture(UUID task, DeploymentAgentActionLog.Snapshot saved, DeploymentRemoteLayout layout,
                                             List<DeploymentAgentActionLog.Event> events) {
        try {
            if (!Set.of(4, 5).contains(saved.executionSemantics()) || !Set.of("UNKNOWN", "CANCELLED").contains(saved.state())
                || events.isEmpty() || events.size() > 9996) return Optional.empty();
            DeploymentAgentWorkspaceBinding.find(task, saved, events);
            long prior = 0;
            for (var e : events) {
                if (e.id() <= prior || !TYPES.contains(e.type()) || e.action() == null || e.detail() == null)
                    return Optional.empty();
                prior = e.id();
            }
            var source = one(events, "SOURCE");
            var profile = one(events, "TARGET_PROFILE");
            var target = one(events, "REMOTE_TARGET");
            var policy = one(events, "REMEMBER_AUTHORIZATION");
            var header = one(events, "REMOTE_LAYOUT");
            if (!sha(source.action()) || !sha(source.detail()) || !profile.action().isEmpty() || !sha(profile.detail())
                || !target.action().isEmpty() || !sha(target.detail()) || !policy.action().equals("rememberAuthorization.v1")
                || !policy.detail().equals(Boolean.toString(saved.identity().rememberAuthorization())))
                return Optional.empty();
            var canonical = JSON.createObjectNode().put("formatVersion", 1).put("task", task.toString()).put("server", saved.identity().server());
            canonical.set("layout", JSON.readTree(layout.canonical()));
            canonical.put("layoutSha256", layout.digest());
            if (!header.action().equals(task.toString()) || !header.detail().equals(canonical.toString()))
                return Optional.empty();
            String application = oneApplication(events);
            var plans = events.stream().filter(e -> e.type().equals("PLAN")).toList();
            if (plans.isEmpty() || plans.stream().anyMatch(e -> !e.action().isEmpty() || !sha(e.detail())))
                return Optional.empty();
            var preparations = events.stream().filter(e -> e.type().equals("PREPARE_EXECUTING")).toList();
            if (preparations.size() < 2 || preparations.size() > 16) return Optional.empty();
            var result = new ArrayList<Member>();
            var components = new HashSet<String>();
            var actions = new HashSet<String>();
            for (var prepare : preparations) {
                if (!prepare.detail().matches("[a-z0-9][a-z0-9-]{0,62}") || !components.add(prepare.detail()) || !actions.add(prepare.action()))
                    return Optional.empty();
                var plan = plans.stream().filter(e -> e.id() < prepare.id()).reduce((a, b) -> b).orElseThrow();
                if (events.stream().noneMatch(e -> e.type().equals("PLAN_APPLICATION") && e.id() < plan.id()))
                    return Optional.empty();
                for (var h : List.of(source, profile, target, policy, header))
                    if (h.id() >= plan.id()) return Optional.empty();
                var quotas = events.stream().filter(e -> e.type().equals("WORKSPACE_QUOTA") && e.id() > plan.id() && e.id() < prepare.id()).toList();
                if (quotas.isEmpty() || !prepare.action().equals(DeploymentAgentPreparationBinding.preparationAction(task, saved.identity(), source, plan, prepare, layout.digest(), quotas)))
                    return Optional.empty();
                var quota = quotas.stream().filter(e -> e.action().equals(prepare.detail())).findFirst().orElseThrow();
                var ended = events.stream().filter(e -> e.type().equals("PREPARE_RESULT") && e.action().equals(prepare.action())).toList();
                if (ended.size() > 1 || !ended.isEmpty() && (ended.getFirst().id() <= prepare.id() || !sha(ended.getFirst().detail())))
                    return Optional.empty();
                var workspace = DeploymentAgentWorkspaceBinding.workspace(task, saved, events,
                    DeploymentManagedUnitResolver.managedId(application, prepare.detail()), source.action(), layout);
                var candidates = events.stream().filter(e -> e.type().equals("CANDIDATE") && e.action().equals(prepare.detail())).toList();
                if (ended.isEmpty()) {
                    if (!candidates.isEmpty() || !prepare.equals(preparations.getLast())) return Optional.empty();
                } else {
                    if (candidates.size() != 1 || candidates.getFirst().id() <= ended.getFirst().id()
                        || !candidates.getFirst().detail().equals(workspace.candidateId()) || candidates.getFirst().id() >= preparations.getLast().id())
                        return Optional.empty();
                    var candidateQuota = events.stream().filter(e -> e.type().equals("CANDIDATE_QUOTA") && e.action().equals(prepare.detail())).toList();
                    if (candidateQuota.size() != 1 || candidateQuota.getFirst().id() <= prepare.id() || candidateQuota.getFirst().id() >= ended.getFirst().id())
                        return Optional.empty();
                    var q = DeploymentAgentPreparationBinding.quotaJson(candidateQuota.getFirst().detail());
                    DeploymentAgentPreparationBinding.requireQuotaFields(q, true);
                    DeploymentAgentPreparationBinding.requireQuotaOwner(q, task, saved.identity(), source, layout.digest(), plan.detail());
                    var declared = DeploymentAgentPreparationBinding.quotaJson(quota.detail());
                    if (!q.path("candidate").asText().equals(workspace.candidateId()) || !q.path("prepareAction").asText().equals(prepare.action())
                        || !q.path("workspaceBytes").equals(declared.path("workspaceBytes")) || !q.path("reserveBytes").equals(declared.path("reserveBytes")))
                        return Optional.empty();
                }
                result.add(new Member(prepare, plan, quota, workspace, ended.isEmpty()));
            }
            if (result.stream().filter(Member::pending).count() != 1) return Optional.empty();
            var pending = result.getLast();
            if (!pending.pending()) return Optional.empty();
            if (events.stream().filter(e -> e.type().equals("PREPARE_RESULT")).count() != result.size() - 1
                || events.stream().filter(e -> e.type().equals("CANDIDATE")).count() != result.size() - 1
                || events.stream().filter(e -> e.type().equals("CANDIDATE_QUOTA")).count() != result.size() - 1)
                return Optional.empty();
            for (var q : events.stream().filter(e -> e.type().equals("WORKSPACE_QUOTA")).toList()) {
                var plan = plans.stream().filter(e -> e.id() < q.id()).reduce((a, b) -> b).orElseThrow();
                var node = DeploymentAgentPreparationBinding.quotaJson(q.detail());
                DeploymentAgentPreparationBinding.requireQuotaFields(node, false);
                DeploymentAgentPreparationBinding.requireQuotaOwner(node, task, saved.identity(), source, layout.digest(), plan.detail());
                long firstPrepare = result.stream().filter(m -> m.plan().equals(plan)).mapToLong(m -> m.preparation().id()).min().orElseThrow();
                if (!q.action().matches("[a-z0-9][a-z0-9-]{0,62}") || q.id() >= firstPrepare) return Optional.empty();
            }
            for (var e : events) {
                if (e.type().equals("STATE") && (!e.action().isEmpty() || !Set.of("RUNNING", "WAITING", "UNKNOWN", "CANCELLED").contains(e.detail())))
                    return Optional.empty();
                if (e.id() > pending.preparation().id() && !Set.of("STATE", "RECOVERY_REVIEW_BOUND", "RECOVERY_REVIEW_REBOUND").contains(e.type()))
                    return Optional.empty();
            }
            var states = events.stream().filter(e -> e.type().equals("STATE")).toList();
            if (states.isEmpty() || states.getLast().id() <= pending.preparation().id() || !states.getLast().detail().equals(saved.state()))
                return Optional.empty();
            validateReviewMetadata(saved.identity(), events, pending.preparation().id());
            var otherEffects = new HashMap<List<String>, Integer>();
            for (var e : events) {
                for (String kind : List.of("COMMAND", "CAPABILITY", "SEAL")) {
                    var key = List.of(kind, e.action());
                    if (e.type().equals(kind + "_EXECUTING")) otherEffects.merge(key, 1, Integer::sum);
                    if (e.type().equals(kind + "_RESULT")) {
                        Integer count = otherEffects.get(key);
                        if (count == null) return Optional.empty();
                        if (count == 1) otherEffects.remove(key);
                        else otherEffects.put(key, count - 1);
                    }
                }
                if (Set.of("CURRENT_SOURCE", "SEALED_ARTIFACT").contains(e.type())
                    && (!sha(e.detail()) || result.stream().noneMatch(m -> !m.pending() && m.preparation().detail().equals(e.action())
                    && events.stream().anyMatch(candidate -> candidate.type().equals("CANDIDATE") && candidate.action().equals(e.action()) && candidate.id() < e.id()))))
                    return Optional.empty();
            }
            if (!otherEffects.isEmpty()) return Optional.empty();
            if (!DeploymentAgentActionLog.effectsSettled(events.stream().filter(e -> !e.type().startsWith("PREPARE_")).toList()))
                return Optional.empty();
            var captured = new Captured(task, saved, layout, events, result);
            var worst = rows(captured);
            for (var row : worst)
                ((ObjectNode) row).put("phase", "RECONCILED_ABSENT").put("before", "PRESENT").put("beforeResult", "f".repeat(64)).put("afterResult", "f".repeat(64));
            bounded(progress(captured, worst), 12000);
            bounded(proofNode(captured, worst), 8192);
            return Optional.of(captured);
        } catch (Exception invalid) {
            return Optional.empty();
        }
    }

    private static void validateReviewMetadata(DeploymentAgentActionLog.Identity identity, List<DeploymentAgentActionLog.Event> events, long pending) {
        long previous = 0;
        String revision = null;
        for (var e : events.stream().filter(v -> v.type().startsWith("RECOVERY_REVIEW_")).toList()) {
            if (e.id() <= pending || !e.action().equals(identity.configurationDigest()))
                throw new IllegalArgumentException();
            if (revision == null) {
                if (!e.type().equals("RECOVERY_REVIEW_BOUND") || !sha(e.detail())) throw new IllegalArgumentException();
                revision = e.detail();
            } else {
                String prefix = "previousEventId=" + previous + "\npreviousReviewRevision=" + revision + "\nreviewRevision=";
                if (!e.type().equals("RECOVERY_REVIEW_REBOUND") || !e.detail().startsWith(prefix) || !sha(e.detail().substring(prefix.length())) || e.detail().substring(prefix.length()).equals(revision))
                    throw new IllegalArgumentException();
                revision = e.detail().substring(prefix.length());
            }
            previous = e.id();
        }
    }

    public static void requireNoBusinessReference(Connection connection, Captured captured) throws Exception {
        for (var member : captured.members())
            DeploymentAgentPreparationCompensation.requireNoBusinessReference(connection, captured.view(member));
    }

    private static ArrayNode rows(Captured c) {
        var rows = JSON.createArrayNode();
        for (var m : c.members())
            rows.addObject().put("candidate", m.workspace().candidateId()).put("phase", "INTENT").put("before", "UNOBSERVED")
                .put("beforeRequest", beforeRequest(c, m)).put("beforeResult", "")
                .put("cleanupRequest", DeploymentAgentProjectChannel.originalCleanupRequestDigest(c.task(), c.saved().identity().server(), c.saved().identity().configurationDigest(), m.workspace()))
                .put("afterRequest", DeploymentAgentPartialPreparationProbe.requestDigest(c.view(m), true)).put("afterResult", "");
        return rows;
    }

    private static String beforeRequest(Captured c, Member m) {
        return m.pending() ? DeploymentAgentPartialPreparationProbe.requestDigest(c.view(m), false)
            : DeploymentAgentProjectChannel.originalStatusRequestDigest(c.task(), c.saved().identity().server(), c.saved().identity().configurationDigest(), m.workspace());
    }

    private static ObjectNode progress(Captured c, JsonNode rows) {
        var n = JSON.createObjectNode().put("format", "WTL_AGENT_SET_PROGRESS_1").put("task", c.task().toString())
            .put("server", c.saved().identity().server()).put("layout", c.layout().digest()).put("origin", c.originSha256());
        n.set("members", rows.deepCopy());
        return n;
    }

    private static ObjectNode proofNode(Captured c, JsonNode rows) {
        var n = JSON.createObjectNode().put("formatVersion", 1).put("task", c.task().toString()).put("server", c.saved().identity().server())
            .put("target", c.saved().identity().targetDigest()).put("configuration", c.saved().identity().configurationDigest())
            .put("approvalMode", c.saved().identity().approvalMode()).put("state", c.saved().state()).put("semantics", c.saved().executionSemantics())
            .put("layout", c.layout().digest()).put("prefixSha256", DeploymentAgentPreparationAbsence.digest(c.events()))
            .put("pendingEvent", c.pending().preparation().id()).put("pendingAction", c.pending().preparation().action())
            .put("progressSha256", DeploymentAgentPreparationAbsence.hash(progress(c, rows).toString()));
        var a = n.putArray("members");
        for (int i = 0; i < c.members().size(); i++) {
            var m = c.members().get(i);
            var item = a.addObject().put("component", m.preparation().detail()).put("candidate", m.workspace().candidateId())
                .put("prepareEvent", m.preparation().id()).put("prepareAction", m.preparation().action()).put("plan", m.plan().detail())
                .put("quotaSha256", DeploymentAgentPreparationAbsence.hash(m.quota().detail())).put("pending", m.pending());
            item.set("receipt", rows.get(i).deepCopy());
        }
        return n;
    }

    private static final Set<String> OBSERVATION = Set.of("candidate", "phase", "form", "beforeRequest", "beforeResult", "afterResult");

    public static long pendingWorkspaceBytes(Captured c) throws Exception {
        return DeploymentAgentPreparationBinding.quotaJson(c.pending().quota().detail()).path("workspaceBytes").longValue();
    }

    public static String typedRequestDigest(Captured c) throws Exception {
        return DeploymentAgentPartialPreparationProbe.stageRequestDigest(c.view(c.pending()), pendingWorkspaceBytes(c));
    }

    private static ObjectNode observation(Captured c) throws Exception {
        return JSON.createObjectNode().put("candidate", c.pending().workspace().candidateId()).put("phase", "INTENT")
            .put("form", "UNOBSERVED").put("beforeRequest", typedRequestDigest(c)).put("beforeResult", "").put("afterResult", "");
    }

    private static ObjectNode progress2(Captured c, JsonNode rows, JsonNode observation) {
        var n = progress(c, rows).put("format", "WTL_AGENT_SET_PROGRESS_2");
        n.set("pendingObservation", observation.deepCopy());
        return n;
    }

    private static ObjectNode proof2(Captured c, JsonNode rows, JsonNode observation) {
        var n = proofNode(c, rows).put("formatVersion", 2)
            .put("progressSha256", DeploymentAgentPreparationAbsence.hash(progress2(c, rows, observation).toString()));
        n.set("pendingObservation", observation.deepCopy());
        return n;
    }

    private static void requireTypedBounds(Captured c, JsonNode rows) throws Exception {
        var worst = (ArrayNode) rows.deepCopy();
        for (int i = 0; i < c.members().size(); i++)
            if (!c.members().get(i).pending())
                ((ObjectNode) worst.get(i)).put("phase", "RECONCILED_ABSENT").put("before", "PRESENT")
                    .put("beforeResult", "f".repeat(64)).put("afterResult", "f".repeat(64));
        var sidecar = observation(c).put("phase", "ABSENT_WITHOUT_DISPATCH").put("form", "CREATED_WITHOUT_ARCHIVE")
            .put("beforeResult", "f".repeat(64)).put("afterResult", "f".repeat(64));
        bounded(progress2(c, worst, sidecar), 12000);
        bounded(proof2(c, worst, sidecar), 8192);
    }

    private static void validateV2(Captured c, JsonNode rows, JsonNode sidecar, boolean ended) throws Exception {
        validateRows(c, rows, false);
        var legacy = rows.get(c.members().indexOf(c.pending()));
        if (!legacy.path("phase").asText().equals("INTENT") || !legacy.path("afterResult").asText().isEmpty())
            throw new IllegalArgumentException("Dispatched legacy preparation cannot be upgraded");
        DeploymentJsonDocument.fields(sidecar, OBSERVATION);
        for (String key : OBSERVATION) if (!sidecar.path(key).isTextual()) throw new IllegalArgumentException();
        if (!sidecar.path("candidate").asText().equals(c.pending().workspace().candidateId())
            || !sidecar.path("beforeRequest").asText().equals(typedRequestDigest(c)))
            throw new IllegalArgumentException();
        String phase = sidecar.path("phase").asText(), form = sidecar.path("form").asText(), before = sidecar.path("beforeResult").asText(), after = sidecar.path("afterResult").asText();
        if (!Set.of("INTENT", "DISPATCHED", "SUCCEEDED", "ABSENT", "RECONCILED_ABSENT", "ABSENT_WITHOUT_DISPATCH").contains(phase))
            throw new IllegalArgumentException();
        boolean unobserved = form.equals("UNOBSERVED");
        if (unobserved) {
            if (!phase.equals("INTENT") || !before.isEmpty() || !after.isEmpty()) throw new IllegalArgumentException();
        } else {
            var type = DeploymentAgentPartialPreparationProbe.PreparationStage.valueOf(form);
            if (!before.equals(DeploymentAgentPreparationAbsence.hash(DeploymentAgentPartialPreparationProbe.stageOutput(c.view(c.pending()), type))))
                throw new IllegalArgumentException();
            if (type == DeploymentAgentPartialPreparationProbe.PreparationStage.ABSENT
                ? !Set.of("INTENT", "ABSENT").contains(phase) : phase.equals("ABSENT"))
                throw new IllegalArgumentException();
            if (!after.isEmpty() && !after.equals(DeploymentAgentPreparationAbsence.hash(DeploymentAgentPartialPreparationProbe.absentOutput(c.view(c.pending())))))
                throw new IllegalArgumentException();
            if (Set.of("INTENT", "DISPATCHED").contains(phase) && !after.isEmpty()
                || Set.of("ABSENT", "RECONCILED_ABSENT", "ABSENT_WITHOUT_DISPATCH").contains(phase) && after.isEmpty())
                throw new IllegalArgumentException();
        }
        if (ended) {
            if (unobserved || !Set.of("SUCCEEDED", "ABSENT", "RECONCILED_ABSENT", "ABSENT_WITHOUT_DISPATCH").contains(phase) || after.isEmpty())
                throw new IllegalArgumentException();
            for (int i = 0; i < c.members().size(); i++)
                if (!c.members().get(i).pending()) {
                    var r = rows.get(i);
                    if (!Set.of("SUCCEEDED", "ABSENT", "RECONCILED_ABSENT").contains(r.path("phase").asText())
                        || r.path("before").asText().equals("UNOBSERVED") || r.path("afterResult").asText().isEmpty())
                        throw new IllegalArgumentException();
                }
        }
    }

    /** Only TaskService owns the checkpoint; Mod JDBC never opens platform checkpoint tables. */
    public static final class Progress {
        private final Captured captured;

        private final TaskCheckpoint checkpoint;

        private TaskCheckpoint.Snapshot expected;

        private ObjectNode payload;

        private Progress(Captured c, TaskCheckpoint checkpoint) throws Exception {
            captured = c;
            this.checkpoint = checkpoint;
            expected = checkpoint.snapshot();
            try {
                var parsed = JSON.readTree(expected.payload());
                if (!(parsed instanceof ObjectNode object)) throw new IllegalArgumentException();
                payload = object;
            } catch (Exception invalid) {
                throw new IllegalArgumentException("Original cleanup progress is malformed");
            }
            if (typed()) validateV2(c, payload.path("members"), payload.path("pendingObservation"), false);
            else validateRows(c, payload.path("members"), false);
            if (!(typed() ? progress2(c, payload.path("members"), payload.path("pendingObservation")) : progress(c, payload.path("members"))).toString().equals(expected.payload()))
                throw new IllegalArgumentException("Original cleanup progress changed");
        }

        public static Progress open(ModContext context, TaskContext task, Captured c) throws Exception {
            return open(context, task, c, false);
        }

        public static Progress open(ModContext context, TaskContext task, Captured c, boolean retainedProof) throws Exception {
            if (!task.id().equals(c.task())) throw new IllegalArgumentException("Foreign cleanup progress");
            if (context.tasks().findCheckpoint(task, KEY).isPresent())
                throw new IllegalStateException("Original author checkpoint cannot become recovery progress");
            var retained = context.tasks().findRecoveryCheckpoint(task, KEY);
            if (retainedProof && retained.isEmpty())
                throw new IllegalStateException("Original cleanup checkpoint missing");
            return new Progress(c, retained.isPresent() ? retained.orElseThrow()
                : context.tasks().beginRecoveryCheckpoint(task, KEY, bounded(progress(c, rows(c)), 12000)));
        }

        public void verify() throws Exception {
            if (!expected.equals(checkpoint.snapshot()) || expected.released())
                throw new IllegalStateException("Original cleanup progress changed");
        }

        public boolean typed() {
            return payload.path("format").asText().equals("WTL_AGENT_SET_PROGRESS_2");
        }

        /** CAS adds only one sidecar; no legacy row or request is rewritten. */
        public boolean enableTyped() throws Exception {
            verify();
            if (typed()) return true;
            if (!row(captured.pending()).path("phase").asText().equals("INTENT")
                || !row(captured.pending()).path("afterResult").asText().isEmpty()) return false;
            for (var member : captured.members()) if (!member.pending() && requiresAfter(member)) return false;
            requireTypedBounds(captured, payload.path("members"));
            save(progress2(captured, payload.path("members"), observation(captured)));
            return true;
        }

        private ObjectNode active(Member m) {
            return typed() && m.pending() ? (ObjectNode) payload.path("pendingObservation") : row(m);
        }

        private ObjectNode active(ObjectNode next, Member m) {
            return typed() && m.pending() ? (ObjectNode) next.path("pendingObservation")
                : (ObjectNode) next.path("members").get(captured.members().indexOf(m));
        }

        public boolean finished(Member m) {
            var r = active(m);
            return Set.of("SUCCEEDED", "ABSENT", "RECONCILED_ABSENT", "ABSENT_WITHOUT_DISPATCH").contains(r.path("phase").asText())
                && !r.path("afterResult").asText().isEmpty();
        }

        public boolean requiresAfter(Member m) {
            return Set.of("DISPATCHED", "SUCCEEDED").contains(active(m).path("phase").asText()) && !finished(m);
        }

        private ObjectNode row(Member m) {
            return (ObjectNode) payload.path("members").get(captured.members().indexOf(m));
        }

        private void save(ObjectNode next) throws Exception {
            verify();
            var observed = checkpoint.advance(expected, bounded(next, 12000));
            expected = observed;
            payload = next;
        }

        public boolean before(Member m, CommandResult result) throws Exception {
            verify();
            boolean present;
            if (m.pending() && typed()) {
                var value = DeploymentAgentPartialPreparationProbe.stage(captured.view(m), result)
                    .orElseThrow(() -> new IllegalArgumentException("Original typed preparation observation is incomplete"));
                var sidecar = active(m);
                if (!sidecar.path("phase").asText().equals("INTENT"))
                    throw new IllegalStateException("Dispatched cleanup cannot be replayed");
                String form = sidecar.path("form").asText();
                if (!form.equals("UNOBSERVED") && !form.equals(value.name())) {
                    if (value == DeploymentAgentPartialPreparationProbe.PreparationStage.ABSENT) return false;
                    throw new IllegalArgumentException("Original preparation stage changed");
                }
                if (form.equals("UNOBSERVED")) {
                    var next = payload.deepCopy();
                    active(next, m).put("form", value.name()).put("beforeResult", DeploymentAgentPreparationAbsence.hash(result.stdout()));
                    save(next);
                }
                return value != DeploymentAgentPartialPreparationProbe.PreparationStage.ABSENT;
            }
            if (m.pending()) {
                present = DeploymentAgentPartialPreparationProbe.present(captured.view(m), result);
                if (!present && !DeploymentAgentPartialPreparationProbe.absent(captured.view(m), result))
                    throw new IllegalArgumentException("Original pending observation is incomplete");
            } else {
                if (!complete(result))
                    throw new IllegalArgumentException("Original candidate observation is incomplete");
                present = result.stdout().equals("CANDIDATE_STATE=owned\nBUILD_ACTIVE=no\n");
                if (!present && !result.stdout().equals("CANDIDATE_STATE=absent\nBUILD_ACTIVE=no\n"))
                    throw new IllegalArgumentException("Original candidate is active or unconfirmed");
            }
            var next = payload.deepCopy();
            var r = (ObjectNode) next.path("members").get(captured.members().indexOf(m));
            r.put("before", present ? "PRESENT" : "ABSENT").put("beforeResult", DeploymentAgentPreparationAbsence.hash(result.stdout())).put("afterResult", "");
            save(next);
            if (present && !row(m).path("phase").asText().equals("INTENT"))
                throw new IllegalStateException("Dispatched cleanup cannot be replayed");
            return present;
        }

        public void dispatched(Member m) throws Exception {
            verify();
            var selected = active(m);
            boolean present = typed() && m.pending() ? Set.of("UPLOADED", "CREATED_WITHOUT_ARCHIVE").contains(selected.path("form").asText())
                : selected.path("before").asText().equals("PRESENT");
            if (!selected.path("phase").asText().equals("INTENT") || !present)
                throw new IllegalStateException("Original cleanup cannot dispatch");
            var next = payload.deepCopy();
            active(next, m).put("phase", "DISPATCHED");
            save(next);
        }

        public void result(Member m, boolean succeeded) throws Exception {
            verify();
            if (!active(m).path("phase").asText().equals("DISPATCHED"))
                throw new IllegalStateException("Cleanup send boundary missing");
            if (succeeded) {
                var next = payload.deepCopy();
                active(next, m).put("phase", "SUCCEEDED");
                save(next);
            }
        }

        public void after(Member m, CommandResult result) throws Exception {
            verify();
            if (!DeploymentAgentPartialPreparationProbe.absent(captured.view(m), result))
                throw new IllegalArgumentException("Original candidate absence is incomplete");
            var next = payload.deepCopy();
            var r = active(next, m);
            String phase = r.path("phase").asText();
            if (typed() && m.pending()) {
                String form = r.path("form").asText();
                if (form.equals("UNOBSERVED")) throw new IllegalArgumentException("Original typed observation missing");
                r.put("phase", phase.equals("DISPATCHED") ? "RECONCILED_ABSENT" : phase.equals("INTENT")
                        ? form.equals("ABSENT") ? "ABSENT" : "ABSENT_WITHOUT_DISPATCH" : phase)
                    .put("afterResult", DeploymentAgentPreparationAbsence.hash(result.stdout()));
                save(next);
                return;
            }
            if (phase.equals("INTENT") && !r.path("before").asText().equals("ABSENT"))
                throw new IllegalArgumentException("Original cleanup receipt missing");
            r.put("phase", phase.equals("DISPATCHED") ? "RECONCILED_ABSENT" : phase.equals("INTENT") ? "ABSENT" : phase)
                .put("afterResult", DeploymentAgentPreparationAbsence.hash(result.stdout()));
            save(next);
        }

        public String proof() throws Exception {
            verify();
            if (typed()) {
                validateV2(captured, payload.path("members"), payload.path("pendingObservation"), true);
                return bounded(proof2(captured, payload.path("members"), payload.path("pendingObservation")), 8192);
            }
            validateRows(captured, payload.path("members"), true);
            return bounded(proofNode(captured, payload.path("members")), 8192);
        }

        public String payloadSha256() throws Exception {
            verify();
            return DeploymentAgentPreparationAbsence.hash(expected.payload());
        }

        public void release(DeploymentAgentActionLog.Event event, List<DeploymentAgentActionLog.Event> events) throws Exception {
            if (!matches(event, events) || !JSON.readTree(event.detail()).path("progressSha256").asText().equals(DeploymentAgentPreparationAbsence.hash(checkpoint.snapshot().payload()))
                || !checkpoint.snapshot().payload().equals(expected.payload()))
                throw new IllegalStateException("Original cleanup proof does not own its checkpoint");
            if (!checkpoint.snapshot().released()) {
                verify();
                checkpoint.close();
                expected = checkpoint.snapshot();
            }
        }
    }

    private static void validateRows(Captured c, JsonNode rows, boolean ended) throws Exception {
        if (!rows.isArray() || rows.size() != c.members().size()) throw new IllegalArgumentException();
        var original = rows(c);
        for (int i = 0; i < rows.size(); i++) {
            var r = rows.get(i);
            var m = c.members().get(i);
            DeploymentJsonDocument.fields(r, ROW);
            for (String key : ROW) if (!r.path(key).isTextual()) throw new IllegalArgumentException();
            for (String key : List.of("candidate", "beforeRequest", "cleanupRequest", "afterRequest"))
                if (!r.path(key).equals(original.get(i).path(key))) throw new IllegalArgumentException();
            String phase = r.path("phase").asText(), before = r.path("before").asText();
            if (!Set.of("INTENT", "DISPATCHED", "SUCCEEDED", "ABSENT", "RECONCILED_ABSENT").contains(phase)
                || !Set.of("UNOBSERVED", "PRESENT", "ABSENT").contains(before)) throw new IllegalArgumentException();
            String expectedBefore = before.equals("UNOBSERVED") ? "" : m.pending()
                ? before.equals("PRESENT") ? DeploymentAgentPartialPreparationProbe.presentOutput(c.view(m)) : DeploymentAgentPartialPreparationProbe.absentOutput(c.view(m))
                : "CANDIDATE_STATE=" + (before.equals("PRESENT") ? "owned" : "absent") + "\nBUILD_ACTIVE=no\n";
            if (!r.path("beforeResult").asText().equals(before.equals("UNOBSERVED") ? "" : DeploymentAgentPreparationAbsence.hash(expectedBefore))
                || !r.path("afterResult").asText().isEmpty() && !r.path("afterResult").asText().equals(DeploymentAgentPreparationAbsence.hash(DeploymentAgentPartialPreparationProbe.absentOutput(c.view(m)))))
                throw new IllegalArgumentException();
            if (!phase.equals("INTENT") && before.equals("UNOBSERVED") || phase.equals("ABSENT") && !before.equals("ABSENT")
                || before.equals("UNOBSERVED") && !r.path("afterResult").asText().isEmpty()
                || Set.of("ABSENT", "RECONCILED_ABSENT").contains(phase) && r.path("afterResult").asText().isEmpty())
                throw new IllegalArgumentException();
            if (ended && (!Set.of("SUCCEEDED", "ABSENT", "RECONCILED_ABSENT").contains(phase) || before.equals("UNOBSERVED") || r.path("afterResult").asText().isEmpty()
                || phase.equals("ABSENT") && !before.equals("ABSENT"))) throw new IllegalArgumentException();
        }
    }

    public static boolean matches(DeploymentAgentActionLog.Event event, List<DeploymentAgentActionLog.Event> events) {
        try {
            if (!event.type().equals(EVENT) || !events.contains(event) || events.stream().filter(e -> e.type().equals(EVENT)).count() != 1
                || event.detail().getBytes(StandardCharsets.UTF_8).length > 8192) return false;
            var n = JSON.readTree(event.detail());
            UUID task = UUID.fromString(n.path("task").asText());
            var prefix = events.stream().filter(e -> e.id() < event.id()).toList();
            var identity = new DeploymentAgentActionLog.Identity(n.path("server").asText(), n.path("target").asText(), n.path("configuration").asText(),
                n.path("approvalMode").asText(), Boolean.parseBoolean(one(prefix, "REMEMBER_AUTHORIZATION").detail()));
            var layout = DeploymentRemoteLayout.decode(JSON.readTree(one(prefix, "REMOTE_LAYOUT").detail()).path("layout").toString());
            var c = capture(task, new DeploymentAgentActionLog.Snapshot(identity, n.path("state").asText(), n.path("semantics").asInt()), layout, prefix).orElseThrow();
            var rows = JSON.createArrayNode();
            for (var m : n.path("members")) rows.add(m.path("receipt"));
            if (!n.path("formatVersion").isInt()) return false;
            ObjectNode canonical;
            if (n.path("formatVersion").intValue() == 2) {
                validateV2(c, rows, n.path("pendingObservation"), true);
                canonical = proof2(c, rows, n.path("pendingObservation"));
            } else {
                validateRows(c, rows, true);
                canonical = proofNode(c, rows);
            }
            if (!event.action().equals(c.pending().preparation().action()) || !canonical.toString().equals(event.detail()))
                return false;
            var suffix = events.stream().filter(e -> e.id() > event.id()).toList();
            String outcome = c.saved().state().equals("CANCELLED") ? "CANCELLED" : "FAILED";
            return suffix.size() <= 2 && (suffix.isEmpty() || suffix.getFirst().type().equals("AI_RECOVERY_SETTLED") && suffix.getFirst().action().equals(task.toString())
                && suffix.getFirst().detail().equals(outcome + ":COMMAND_NATIVE_DATABASE_SECRET_CANDIDATE_SOURCE_SETTLED"))
                && (suffix.size() < 2 || suffix.getLast().type().equals("STATE") && suffix.getLast().action().isEmpty() && suffix.getLast().detail().equals(outcome));
        } catch (Exception invalid) {
            return false;
        }
    }

    public static Optional<Captured> retained(UUID task, DeploymentAgentActionLog.Snapshot saved, DeploymentRemoteLayout layout,
                                              List<DeploymentAgentActionLog.Event> events) {
        try {
            var proof = events.stream().filter(e -> e.type().equals(EVENT)).toList();
            if (proof.size() != 1 || !matches(proof.getFirst(), events)) return Optional.empty();
            var n = JSON.readTree(proof.getFirst().detail());
            var c = capture(task, new DeploymentAgentActionLog.Snapshot(saved.identity(), n.path("state").asText(), saved.executionSemantics()), layout,
                events.stream().filter(e -> e.id() < proof.getFirst().id()).toList()).orElseThrow();
            if (!n.path("task").asText().equals(task.toString()) || !n.path("target").asText().equals(saved.identity().targetDigest())
                || !n.path("configuration").asText().equals(saved.identity().configurationDigest()) || !n.path("server").asText().equals(saved.identity().server())
                || !n.path("layout").asText().equals(layout.digest()) || !n.path("approvalMode").asText().equals(saved.identity().approvalMode()))
                return Optional.empty();
            return Optional.of(c);
        } catch (Exception invalid) {
            return Optional.empty();
        }
    }

    private static boolean complete(CommandResult r) {
        return r != null && !r.truncated() && r.exitCode().equals(Optional.of(0)) && r.stderr().isEmpty();
    }

    private static String oneApplication(List<DeploymentAgentActionLog.Event> events) {
        var apps = events.stream().filter(e -> e.type().equals("PLAN_APPLICATION")).toList();
        if (apps.isEmpty() || apps.stream().anyMatch(e -> !e.action().isEmpty() || !e.detail().matches("[a-z0-9][a-z0-9-]{0,62}") || !e.detail().equals(apps.getFirst().detail())))
            throw new IllegalArgumentException();
        return apps.getFirst().detail();
    }

    private static DeploymentAgentActionLog.Event one(List<DeploymentAgentActionLog.Event> events, String type) {
        var values = events.stream().filter(e -> e.type().equals(type)).toList();
        if (values.size() != 1) throw new IllegalArgumentException();
        return values.getFirst();
    }

    private static boolean sha(String s) {
        return s != null && s.matches("[0-9a-f]{64}");
    }

    private static String bounded(JsonNode n, int limit) {
        String text = n.toString();
        if (text.length() > limit || text.getBytes(StandardCharsets.UTF_8).length > limit)
            throw new IllegalArgumentException("Original set evidence exceeds its bound");
        return text;
    }
}
