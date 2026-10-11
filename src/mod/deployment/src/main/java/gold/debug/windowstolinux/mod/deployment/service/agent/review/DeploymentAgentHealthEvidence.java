package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.linux.session.*;
import gold.debug.windowstolinux.appapi.linux.transfer.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.ai.review.*;
import gold.debug.windowstolinux.mod.deployment.db.execution.*;
import gold.debug.windowstolinux.mod.deployment.db.reference.DeploymentRefIntent;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildCommandChannel;
import gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentOriginalPublishStatus;
import gold.debug.windowstolinux.mod.deployment.linux.runtime.DeploymentWorkloadArguments;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.*;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentPublishResult;

/** Invocation-local Agent graph verification bindings and receipts from its existing original-status queries. */
public final class DeploymentAgentHealthEvidence implements DeploymentCurrentCommandEvidence, RemoteSession {
    private static final String HEALTH = "deployment.publication.health.v1";

    private static final String STATUS = "deployment.publication.original-status.v1";

    private static final String INSPECT = "deployment.publication.inspect.v1";

    private static final String OBSERVE = "deployment.publication.observe.v1";

    private final DeploymentCommandReviewScope original;

    private final DeploymentCommandReviewScope reviewed;

    private final RemoteSession borrowed;

    private final ServerId server;

    private final TaskContext task;

    private final String revision;

    private final DeploymentPublishResult proposal;

    private final DeploymentAgentActionLog journal;

    private final DeploymentAgentActionLog.Snapshot snapshot;

    private final String profile;

    private final String originalSourceRevision;

    private final DeploymentExecutionStore executions;

    private final DeploymentExecutionRecord execution;

    private final Map<DeploymentRefIntent, Optional<UUID>> references;

    private final Map<String, Selection> selections;

    private final Map<String, Observation> observations = new LinkedHashMap<>();

    private Query query;

    private Pending pending;

    private boolean stopped;

    private record Selection(DeploymentApplicationGraph.Part part, DeploymentUnitCommit commit,
                             DeploymentGraphPublisher.Candidate candidate, DeploymentRemoteWorkspace workspace) {
    }

    private record Query(ActionIntent intent, Map<String, Object> request, Selection selected, boolean approved) {
        Query approve() {
            return new Query(intent, request, selected, true);
        }
    }

    private record Observation(Query query, Map<String, Object> result, DeploymentOriginalPublishStatus status,
                               long event, String resultDigest) {
    }

    private record Health(Selection selected, DeploymentHealthCheck check, List<String> policySources) {
    }

    private record RuntimeQuery(Selection selected, String verb) {
    }

    private record Pending(ActionIntent intent, Health health, RuntimeQuery runtime, String evidenceDigest,
                           String reviewDigest,
                           Set<Long> queryEvents, boolean approved) {
        Pending approve() {
            return new Pending(intent, health, runtime, evidenceDigest, reviewDigest, queryEvents, true);
        }
    }

    public DeploymentAgentHealthEvidence(DeploymentCommandReviewScope original, RemoteSession borrowed, ServerId server,
                                         TaskContext task, String revision, DeploymentPublishResult proposal,
                                         Map<String, DeploymentGraphPublisher.Candidate> candidates,
                                         DeploymentAgentActionLog journal) throws Exception {
        this.original = Objects.requireNonNull(original);
        this.borrowed = Objects.requireNonNull(borrowed);
        this.server = Objects.requireNonNull(server);
        this.task = Objects.requireNonNull(task);
        this.revision = Objects.requireNonNull(revision);
        this.proposal = Objects.requireNonNull(proposal);
        this.journal = Objects.requireNonNull(journal);
        snapshot = journal.find(task).orElseThrow();
        profile = unique(journal.events(task), "TARGET_PROFILE");
        originalSourceRevision = unique(journal.events(task), "SOURCE");
        executions = new DeploymentExecutionStore(original.db());
        execution = executions.findForTask(task).orElseThrow();
        references = Map.copyOf(executions.references(execution));
        selections = selectOriginal(Map.copyOf(candidates), journal.events(task));
        reviewed = original.withCurrentEvidence(this);
        requireBinding();
    }

    public ModContext context() {
        return reviewed;
    }

    @Override
    public Map<String, ?> facts(ActionIntent intent, CommandRequest request, TaskContext supplied) throws ApiException {
        String operation = operation(intent);
        if (!covered(operation, request)) return Map.of();
        try {
            if (stopped || supplied != task || query != null || pending != null)
                throw new IllegalStateException("Original health review cannot be replayed or replaced");
            requireBinding();
            if (operation.equals(STATUS)) {
                var selected = selectQuery(intent, request);
                if (observations.containsKey(selected.part().componentId()))
                    throw new IllegalStateException("Original status was already observed in this verification");
                query = new Query(intent, requestFacts(intent, request), selected, false);
                return Map.of();
            }
            var health = operation.equals(HEALTH) ? selectHealth(intent, request) : null;
            var runtime = health == null ? selectRuntime(intent, request) : null;
            requireObservations();
            var selected = health != null ? health.selected() : runtime.selected();
            var events = journal.events(task);
            var facts = new LinkedHashMap<String, Object>();
            facts.put("schema", health != null ? "agent-current-health-evidence.v1" : "agent-current-runtime-evidence.v1");
            facts.put("scope", "THIS_GRAPH_VERIFICATION_ONLY; retained events are references, never current receipts or execution permission");
            facts.put("currentTask", task.id().toString());
            facts.put("originalTask", task.id().toString());
            facts.put(health != null ? "healthExactActionSha256" : "runtimeExactActionSha256", DeploymentAgentInputs.hash(intent.exactAction()));
            facts.put("server", server.id());
            facts.put("targetProfileSha256", profile);
            facts.put("authenticatedTargetSha256", snapshot.identity().targetDigest());
            facts.put("originalConfigurationRevision", snapshot.identity().configurationDigest());
            facts.put("currentReviewConfigurationRevision", revision);
            facts.put("originalExecution", execution.identity().executionId().toString());
            facts.put("originalReviewedRevision", execution.identity().reviewedRevision());
            facts.put("originalSourceRevision", originalSourceRevision);
            facts.put("originalRequestSha256", DeploymentAgentInputs.hash(execution.requestJson()));
            facts.put("proposalSha256", DeploymentAgentInputs.hash(proposal.toString()));
            facts.put("application", proposal.graph().applicationId());
            facts.put("healthOwner", proposal.graph().healthComponentId());
            facts.put("unitBinding", unitFacts(selected));
            if (health != null) {
                facts.put("healthPolicySources", health.policySources());
                facts.put("typedHealthPolicy", healthFacts(health.check()));
                facts.put("argumentBinding", Map.of("verb", "application-health", "applicationIdArgument", selected.part().unit().id(),
                    "ownershipManifestArgument", selected.part().unit().ownershipManifestSha256(),
                    "healthPayloadArgument", DeploymentWorkloadArguments.healthPayload(health.check()),
                    "healthPayloadMeaning", "Canonical Base64 NUL-framed health-v1 policy; not an application id, resource id or permission"));
            } else {
                var unit = selected.part().unit();
                facts.put("argumentBinding", Map.of("verb", runtime.verb(), "argumentCount", 2,
                    "applicationIdArgument", unit.id(), "applicationIdMeaning", "Argument 1 is the original managed unit's application id",
                    "ownershipManifestArgument", unit.ownershipManifestSha256(),
                    "ownershipManifestMeaning", "Argument 2 is the original ownership manifest SHA256 checked against CURRENT/.windowstolinux-owner; not a deployment id or resource id",
                    "resourceTargetKind", "deployment-release", "resourceTargetId", unit.releaseRoot(),
                    "resourceTargetMeaning", "The unchanged resource target is this unit's managed release root; it is not either positional argument"));
                facts.put("runtimeBinding", Map.of("kind", backend(selected).equals("native") ? "deployment" : "container",
                    "engine", engine(selected), "mode", selected.part().reviewedRuntime().workload().mode().name(),
                    "currentReleaseSha256", selected.commit().release().sha256(), "systemdUnit", unit.systemdUnit()));
            }
            facts.put("originalGraphBindings", selections.values().stream().map(DeploymentAgentHealthEvidence::unitFacts).toList());
            facts.put("currentGraphQueries", observations.values().stream().map(value -> Map.of("event", value.event(),
                "component", value.query().selected().part().componentId(), "request", value.query().request(), "result", value.result(),
                "parsedStatus", statusFacts(value.status()), "resultSha256", value.resultDigest())).toList());
            facts.put("helperSha256", DeploymentManagedHelperBundle.EXPECTED_SHA256);
            facts.put("helperProtocol", DeploymentManagedHelperBundle.PROTOCOL_VERSION);
            if (health != null)
                facts.put("enforcedHealthBoundary", "The pinned application-health implementation loads this application's CURRENT under its managed release root, checks its ownership manifest and native unit/container binding, then parses the typed health-v1 payload. Native HTTP/TCP/UDP checks listener PID membership in the managed systemd cgroup; container probes check the bound container ports and namespace. HTTP requires the loopback response and unchanged process identity. COMMAND or on-demand verification executes the original typed entry/argv under the application identity and must also be reviewed as execution.");
            else
                facts.put("enforcedRuntimeBoundary", "The pinned inspect-runtime and native observe-deployment implementations validate the application id and ownership digest, resolve CURRENT strictly inside this application's managed releases directory and check CURRENT/.windowstolinux-owner. Native checks also verify the original derived systemd unit fragment path, absence of foreign drop-ins and expected unit bytes before reading runtime mode/state. These are helper execution-time guards; the captured original-status receipt is the earlier current-release observation and does not prove present listener ownership. The container inline observation retains its existing independent command review without this supplement.");
            facts.put("observationLimits", "The captured status proves owned CURRENT and original candidate ownership/build state at this invocation's query. It does not prove listener ownership or health; the subsequent helper enforces those checks at execution. Sealed source/artifact and configuration identities are retained original facts, not freshly rehashed remote content. Artifact SHA and release SHA identify different objects. No historical approval is reused and no unresolved outer delivery is declared settled.");
            facts.put("originalEventReferences", eventReferences(events));
            facts.put("priorDiagnosticLocator", Map.of("task", task.id().toString(), "eventCode", "DEPLOYMENT_AGENT_COMMAND_REVIEW_DENIED",
                "currentRequestExactActionSha256", DeploymentAgentInputs.hash(intent.exactAction()),
                "priorExactActionBinding", "UNKNOWN unless a retained original ledger denial supplies its exact binding",
                "limit", "Task diagnostic locator only; no old record id, reason hash, receipt or permission is synthesized"));
            String evidenceDigest = digest(facts);
            String reviewDigest = digest(Map.of("command", requestFacts(intent, request), "currentEvidenceSha256", evidenceDigest));
            facts.put("evidenceSha256", evidenceDigest);
            facts.put("reviewRequestSha256", reviewDigest);
            pending = new Pending(intent, health, runtime, evidenceDigest, reviewDigest, queryEvents(), false);
            journal.event(task, health != null ? "HEALTH_REVIEW_REQUEST" : "RUNTIME_REVIEW_REQUEST", reviewDigest,
                "exactAction=" + DeploymentAgentInputs.hash(intent.exactAction()) + ";evidence=" + evidenceDigest);
            return Collections.unmodifiableMap(facts);
        } catch (Exception unavailable) {
            stopped = true;
            query = null;
            pending = null;
            throw unconfirmed("DEPLOYMENT_AGENT_HEALTH_EVIDENCE_UNCONFIRMED", unavailable);
        }
    }

    @Override
    public void reviewed(ActionIntent intent, CommandRequest request, TaskContext supplied,
                         DeploymentReviewDecision decision) throws ApiException {
        String operation = operation(intent);
        if (!covered(operation, request)) return;
        boolean denial = decision.decision().equals("DENY");
        if (denial) stopped = true;
        try {
            String reviewDigest = !operation.equals(STATUS) && pending != null ? pending.reviewDigest() : digest(requestFacts(intent, request));
            String evidenceDigest = !operation.equals(STATUS) && pending != null ? pending.evidenceDigest() : "none";
            journal.event(task, operation.equals(STATUS) ? denial ? "HEALTH_STATUS_QUERY_DENIED" : "HEALTH_STATUS_QUERY_APPROVED"
                    : operation.equals(HEALTH) ? denial ? "HEALTH_REVIEW_DENIED" : "HEALTH_REVIEW_APPROVED"
                    : denial ? "RUNTIME_REVIEW_DENIED" : "RUNTIME_REVIEW_APPROVED",
                reviewDigest, "exactAction=" + DeploymentAgentInputs.hash(intent.exactAction()) + ";evidence=" + evidenceDigest
                    + ";reasonSha256=" + DeploymentAgentInputs.hash(decision.reason()));
            if (!denial) {
                verify(intent, request, supplied);
                if (operation.equals(STATUS)) query = query.approve();
                else pending = pending.approve();
            }
        } catch (Exception unavailable) {
            stopped = true;
            throw unconfirmed("DEPLOYMENT_AGENT_HEALTH_REVIEW_UNCONFIRMED", unavailable);
        } finally {
            if (denial) {
                query = null;
                pending = null;
            }
        }
    }

    @Override
    public void verify(ActionIntent intent, CommandRequest request, TaskContext supplied) throws ApiException {
        String operation = operation(intent);
        if (!covered(operation, request) && (pending == null || !pending.intent().equals(intent))) return;
        try {
            if (stopped || supplied != task) throw new IllegalStateException("Original health review is stopped");
            requireBinding();
            if (operation.equals(STATUS)) {
                if (query == null || !query.intent().equals(intent) || !query.selected().equals(selectQuery(intent, request)))
                    throw new IllegalStateException("Original query changed");
            } else {
                requireObservations();
                if (pending == null || !pending.intent().equals(intent)
                    || (operation.equals(HEALTH) ? !Objects.equals(pending.health(), selectHealth(intent, request))
                    : !Objects.equals(pending.runtime(), selectRuntime(intent, request)))
                    || !pending.queryEvents().equals(queryEvents()))
                    throw new IllegalStateException("Original health request or query binding changed");
            }
        } catch (Exception changed) {
            stopped = true;
            throw unconfirmed("DEPLOYMENT_AGENT_HEALTH_BINDING_CHANGED", changed);
        }
    }

    private Map<String, Selection> selectOriginal(Map<String, DeploymentGraphPublisher.Candidate> candidates,
                                                  List<DeploymentAgentActionLog.Event> events) {
        String archive = events.stream().filter(value -> value.type().equals("SOURCE")).map(DeploymentAgentActionLog.Event::action)
            .distinct().reduce((left, right) -> {
                throw new IllegalStateException("Conflicting original source archives");
            }).orElseThrow();
        var result = new LinkedHashMap<String, Selection>();
        for (var part : proposal.graph().components()) {
            var candidate = Objects.requireNonNull(candidates.get(part.componentId()));
            var commits = proposal.units().stream().filter(value -> value.unit().equals(part.unit())).toList();
            var workspace = new DeploymentRemoteWorkspace(part.unit().id(), archive);
            if (commits.size() != 1 || !part.unit().server().equals(server) || !workspace.candidateId().equals(candidate.candidateId())
                || !part.unit().equals(DeploymentUnitIdentity.forManaged(part.unit().id(), server, part.unit().ownershipManifestSha256())))
                throw new IllegalStateException("Original unit identity is inconsistent");
            result.put(part.componentId(), new Selection(part, commits.getFirst(), candidate, workspace));
        }
        if (!result.keySet().equals(candidates.keySet()) || result.size() != proposal.units().size())
            throw new IllegalStateException("Original graph coverage changed");
        return Collections.unmodifiableMap(result);
    }

    private Selection selectQuery(ActionIntent intent, CommandRequest request) {
        for (var selected : selections.values()) {
            var unit = selected.part().unit();
            try (var expected = command("publication-original-status", List.of(unit.id(), unit.ownershipManifestSha256(),
                task.id().toString(), selected.candidate().candidateId(), selected.commit().release().sha256(), backend(selected), engine(selected)), 30)) {
                if (matches(intent, request, expected, selected, STATUS)) return selected;
            }
        }
        throw new IllegalArgumentException("Query is not the complete original status request");
    }

    private Health selectHealth(ActionIntent intent, CommandRequest request) {
        for (var selected : selections.values()) {
            var policies = new LinkedHashMap<DeploymentHealthCheck, List<String>>();
            policies.put(selected.part().reviewedRuntime().healthCheck(), new ArrayList<>(List.of("COMPONENT")));
            if (selected.part().componentId().equals(proposal.graph().healthComponentId()))
                policies.computeIfAbsent(proposal.graph().healthCheck(), ignored -> new ArrayList<>()).add("WHOLE_APPLICATION");
            for (var policy : policies.entrySet()) {
                var unit = selected.part().unit();
                try (var expected = command("application-health", List.of(unit.id(), unit.ownershipManifestSha256(),
                    DeploymentWorkloadArguments.healthPayload(policy.getKey())), policy.getKey().timeoutSeconds() + 45L)) {
                    if (matches(intent, request, expected, selected, HEALTH))
                        return new Health(selected, policy.getKey(), List.copyOf(policy.getValue()));
                }
            }
        }
        throw new IllegalArgumentException("Health is not an original unit-bound policy request");
    }

    private RuntimeQuery selectRuntime(ActionIntent intent, CommandRequest request) {
        String operation = operation(intent);
        String verb = operation.equals(INSPECT) ? "inspect-runtime" : "observe-deployment";
        for (var selected : selections.values()) {
            if (operation.equals(OBSERVE) && !backend(selected).equals("native")) continue;
            var unit = selected.part().unit();
            try (var expected = command(verb, List.of(unit.id(), unit.ownershipManifestSha256()), 20)) {
                if (matches(intent, request, expected, selected, operation)) return new RuntimeQuery(selected, verb);
            }
        }
        throw new IllegalArgumentException("Runtime query is not an original canonical helper request");
    }

    private static boolean covered(String operation, CommandRequest request) {
        return Set.of(HEALTH, STATUS, INSPECT).contains(operation)
            || operation.equals(OBSERVE) && request.script().contains(DeploymentManagedHelperBundle.PATH);
    }

    private CommandRequest command(String verb, List<String> arguments, long timeout) {
        var values = new ArrayList<>(List.of(DeploymentManagedHelperBundle.PATH, verb));
        values.addAll(arguments);
        String script = values.stream().map(DeploymentBuildCommandChannel::shellQuote).collect(java.util.stream.Collectors.joining(" "));
        return new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(script), new byte[0], Duration.ofSeconds(timeout), 4096);
    }

    private boolean matches(ActionIntent intent, CommandRequest request, CommandRequest expected, Selection selected, String operation) {
        var actual = RemoteActions.command(server, selected.candidate().artifactSha256(), expected).withSemantics(new ActionSemantics(operation,
            Set.of(new ActionTarget(server.id(), "deployment-release", selected.part().unit().releaseRoot()))));
        return actual.equals(intent) && request.script().equals(expected.script()) && request.timeout().equals(expected.timeout())
            && request.outputLimitBytes() == expected.outputLimitBytes() && request.stdin().length == 0;
    }

    private void requireBinding() throws ApiException {
        try {
            var saved = journal.find(task).orElseThrow();
            var current = executions.findForTask(task).orElseThrow();
            var template = executions.publicationTemplate(current).orElseThrow();
            if (!snapshot.equals(saved) || saved.executionSemantics() != 4 || !saved.identity().server().equals(server.id())
                || !profile.equals(DeploymentAgentInputs.targetProfile(original.servers().get(server, task)))
                || !saved.identity().targetDigest().equals(DeploymentAgentInputs.targetDigest(server, borrowed))
                || !execution.equals(current) || execution.identity().mode() != DeploymentExecutionIdentity.DeploymentExecutionMode.AGENT
                || !execution.identity().taskId().equals(task.id()) || !execution.identity().server().equals(server)
                || !execution.identity().applicationId().equals(proposal.graph().applicationId())
                || !template.graph().equals(proposal.graph()) || !template.units().equals(proposal.units())
                || !references.equals(executions.references(current)))
                throw new IllegalStateException("Original health identity or formal snapshot changed");
            var credentials = new HashMap<gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef,
                gold.debug.windowstolinux.appapi.secret.model.SecretId>();
            selections.values().forEach(value -> credentials.putAll(value.candidate().credentials()));
            if (!references.keySet().equals(Set.copyOf(DeploymentOriginalPublishCommit.references(server, proposal, credentials)))
                || references.values().stream().anyMatch(Optional::isEmpty))
                throw new IllegalStateException("Original formal references changed");
            DeploymentFixedCommandApproval.requireRoute(original, task, revision);
            var events = journal.events(task);
            if (!unique(events, "TARGET_PROFILE").equals(profile) || !unique(events, "SOURCE").equals(originalSourceRevision) || !settledMutations(events))
                throw new IllegalStateException("Original publication or modifying effects are unconfirmed");
            var checkpoint = original.tasks().findCheckpoint(task, DeploymentModelCommandRunner.KEY);
            if (checkpoint.isPresent() && !checkpoint.orElseThrow().snapshot().released())
                throw new IllegalStateException("Original model command is unsettled");
            for (var selected : selections.values()) {
                String component = selected.part().componentId();
                if (!latest(events, "CANDIDATE", component).equals(selected.candidate().candidateId())
                    || !latest(events, "CURRENT_SOURCE", component).equals(selected.candidate().sourceRevision())
                    || !latest(events, "SEALED_ARTIFACT", component).equals(selected.candidate().artifactSha256())
                    || !latest(events, "SEALED_TOOLS", component).equals(selected.candidate().toolchainBindingSha256() + ":" + selected.candidate().hasTools())
                    || !new DeploymentRemoteWorkspace(selected.part().unit().id(), originalArchive(events)).equals(selected.workspace()))
                    throw new IllegalStateException("Original sealed candidate changed");
            }
        } catch (Exception changed) {
            stopped = true;
            throw unconfirmed("DEPLOYMENT_AGENT_HEALTH_ORIGINAL_BINDING_CHANGED", changed);
        }
    }

    private boolean settledMutations(List<DeploymentAgentActionLog.Event> events) {
        var publication = events.stream().filter(value -> value.type().equals("AI_PUBLICATION")
            && value.action().equals(proposal.graph().applicationId())).toList();
        var verified = events.stream().filter(value -> value.type().equals("AI_PUBLICATION_VERIFIED")
            && value.action().equals(proposal.graph().applicationId())).reduce((left, right) -> right);
        var finished = events.stream().filter(value -> value.type().equals("AI_PUBLICATION_FINISHED")
            && value.action().equals(proposal.graph().applicationId())).reduce((left, right) -> right);
        if (publication.size() != 1 || verified.isEmpty() || finished.isEmpty()
            || publication.getFirst().id() >= verified.orElseThrow().id() || verified.orElseThrow().id() >= finished.orElseThrow().id())
            return false;
        if (DeploymentAgentActionLog.effectsSettled(events)) return true;
        if (!DeploymentAgentActionLog.effectsSettled(events.stream().filter(value -> !value.type().startsWith("DELIVERY_")).toList()))
            return false;
        var outer = new ArrayList<DeploymentAgentActionLog.Event>();
        for (var event : events) {
            if (event.type().equals("DELIVERY_EXECUTING")) outer.add(event);
            if (event.type().equals("DELIVERY_RESULT"))
                outer.stream().filter(value -> value.action().equals(event.action())).findFirst().ifPresent(outer::remove);
        }
        return outer.size() == 1 && outer.getFirst().action().matches("[0-9a-f]{64}") && outer.getFirst().detail().matches("[0-9]{1,10}")
            && outer.getFirst().id() < publication.getFirst().id();
    }

    private void requireObservations() {
        if (!observations.keySet().equals(selections.keySet()))
            throw new IllegalStateException("Current graph status is incomplete");
        for (var observed : observations.values()) {
            var selected = observed.query().selected();
            if (!observed.status().current().equals(Optional.of(selected.commit().release().sha256())) || observed.status().buildActive()
                || !observed.status().currentKind().equals(backend(selected).equals("native") ? "deployment" : "container")
                || !observed.status().currentMode().equals(selected.part().reviewedRuntime().workload().mode().name()))
                throw new IllegalStateException("Current graph release or runtime identity differs from its original proposal");
        }
    }

    private DeploymentOriginalPublishStatus parseStatus(Query captured, OperationResult<CommandResult> result) {
        var receipt = result.value().orElseThrow();
        if (result.outcome() != TaskResult.SUCCEEDED || receipt.truncated() || !receipt.exitCode().equals(Optional.of(0))
            || receipt.stdout().getBytes(StandardCharsets.UTF_8).length + receipt.stderr().getBytes(StandardCharsets.UTF_8).length > 4096
            || !receipt.stdout().endsWith("\n"))
            throw new IllegalArgumentException("Current query receipt is incomplete");
        var values = new HashMap<String, String>();
        for (String line : receipt.stdout().split("\n")) {
            int separator = line.indexOf('=');
            if (separator < 1 || !line.substring(0, separator).matches("[A-Za-z_]{1,32}")
                || values.putIfAbsent(line.substring(0, separator), line.substring(separator + 1)) != null)
                throw new IllegalArgumentException("Current query fields are malformed");
        }
        if (!values.keySet().equals(Set.of("PUBLICATION_STATUS", "CURRENT", "CURRENT_KIND", "CURRENT_MODE", "SNAPSHOT", "SNAPSHOT_OLD",
            "SNAPSHOT_KIND", "SNAPSHOT_RUNNING", "MAINTENANCE", "CANDIDATE", "BUILD_ACTIVE", "UNIT_PRESENT", "CONTAINER_PRESENT", "RETAINED_COUNT"))
            || !"1".equals(values.get("PUBLICATION_STATUS")) || !Set.of("none", task.id().toString()).contains(values.get("SNAPSHOT"))
            || !Set.of("none", "0", "1").contains(values.get("SNAPSHOT_RUNNING"))
            || (values.get("SNAPSHOT").equals("none") != values.get("SNAPSHOT_RUNNING").equals("none"))
            || !Set.of("none", "deployment-" + captured.selected().commit().release().sha256()).contains(values.get("MAINTENANCE"))
            || !Set.of("owned", "absent").contains(values.get("CANDIDATE")) || !Set.of("yes", "no").contains(values.get("BUILD_ACTIVE"))
            || !Set.of("yes", "no").contains(values.get("UNIT_PRESENT")) || !Set.of("yes", "no", "not-applicable").contains(values.get("CONTAINER_PRESENT"))
            || (backend(captured.selected()).equals("native") != values.get("CONTAINER_PRESENT").equals("not-applicable"))
            || !values.get("RETAINED_COUNT").matches("0|[1-9][0-9]{0,3}"))
            throw new IllegalArgumentException("Current original-status protocol is invalid");
        return new DeploymentOriginalPublishStatus(optional(values.get("CURRENT")), values.get("CURRENT_KIND"), values.get("CURRENT_MODE"),
            !values.get("SNAPSHOT").equals("none"), optional(values.get("SNAPSHOT_OLD")), values.get("SNAPSHOT_KIND"),
            values.get("SNAPSHOT_RUNNING").equals("none") ? Optional.empty() : Optional.of(values.get("SNAPSHOT_RUNNING").equals("1")),
            !values.get("MAINTENANCE").equals("none"), values.get("CANDIDATE").equals("owned"), values.get("BUILD_ACTIVE").equals("yes"),
            values.get("UNIT_PRESENT").equals("yes"), values.get("CONTAINER_PRESENT").equals("not-applicable") ? Optional.empty()
            : Optional.of(values.get("CONTAINER_PRESENT").equals("yes")), Integer.parseInt(values.get("RETAINED_COUNT")));
    }

    @Override
    public OperationResult<CommandResult> execute(CommandRequest request, ApprovalGrant grant) throws ApiException {
        if (stopped) throw unconfirmed("DEPLOYMENT_AGENT_HEALTH_REVIEW_STOPPED", null);
        requireBinding();
        var selectedQuery = query;
        var selectedCommand = pending;
        boolean statusCall = request.script().contains("'publication-original-status'");
        boolean healthCall = request.script().contains("'application-health'");
        boolean runtimeCall = request.script().contains(DeploymentManagedHelperBundle.PATH)
            && (request.script().contains("inspect-runtime") || request.script().contains("observe-deployment"));
        if (statusCall || healthCall || runtimeCall || selectedQuery != null || selectedCommand != null) {
            var intent = statusCall ? selectedQuery == null ? null : selectedQuery.intent() : selectedCommand == null ? null : selectedCommand.intent();
            if (intent == null || (statusCall ? !selectedQuery.approved() : !selectedCommand.approved()))
                throw unconfirmed("DEPLOYMENT_AGENT_HEALTH_REVIEW_MISSING", null);
            verify(intent, request, task);
            if (statusCall) query = null;
            else pending = null;
        }
        var result = borrowed.execute(request, grant);
        requireBinding();
        if (statusCall) {
            try {
                var status = parseStatus(selectedQuery, result);
                var receipt = result.value().orElseThrow();
                var resultFacts = Map.<String, Object>of("outcome", result.outcome().name(), "exitCode", receipt.exitCode().orElseThrow(),
                    "stdout", receipt.stdout(), "stderr", receipt.stderr(), "truncated", receipt.truncated());
                String resultDigest = digest(resultFacts);
                journal.event(task, "HEALTH_STATUS_QUERY_RESULT", DeploymentAgentInputs.hash(selectedQuery.intent().exactAction()), resultDigest);
                observations.put(selectedQuery.selected().part().componentId(), new Observation(selectedQuery, resultFacts, status,
                    journal.events(task).getLast().id(), resultDigest));
            } catch (Exception unavailable) {
                stopped = true;
                throw unconfirmed("DEPLOYMENT_AGENT_HEALTH_STATUS_UNCONFIRMED", unavailable);
            }
        } else if (healthCall && (result.outcome() != TaskResult.SUCCEEDED || result.value().filter(value -> !value.truncated()
            && value.exitCode().equals(Optional.of(0)) && value.stdout().equals("HEALTHY=1\n")).isEmpty())) {
            stopped = true;
        } else if (runtimeCall && (result.outcome() != TaskResult.SUCCEEDED || result.value().filter(value -> !value.truncated()
            && value.exitCode().equals(Optional.of(0))).isEmpty())) {
            stopped = true;
        }
        return result;
    }

    private static Map<String, Object> unitFacts(Selection selected) {
        var unit = selected.part().unit();
        return Map.ofEntries(Map.entry("component", selected.part().componentId()), Map.entry("applicationId", unit.id()),
            Map.entry("server", unit.server().id()), Map.entry("systemdUnit", unit.systemdUnit()), Map.entry("releaseRoot", unit.releaseRoot()),
            Map.entry("ownershipManifestSha256", unit.ownershipManifestSha256()), Map.entry("candidate", selected.candidate().candidateId()),
            Map.entry("sourceArchiveSha256", selected.workspace().archiveSha256()), Map.entry("sealedSourceRevision", selected.candidate().sourceRevision()),
            Map.entry("sealedArtifactSha256", selected.candidate().artifactSha256()), Map.entry("sealedToolBindingSha256", selected.candidate().toolchainBindingSha256()),
            Map.entry("hasTools", selected.candidate().hasTools()), Map.entry("releaseSha256", selected.commit().release().sha256()),
            Map.entry("runtimeConfigurationSha256", selected.commit().configuration().sha256()), Map.entry("workloadMode", selected.part().reviewedRuntime().workload().mode().name()));
    }

    private static Map<String, Object> healthFacts(DeploymentHealthCheck check) {
        var facts = new LinkedHashMap<String, Object>();
        facts.put("timeoutSeconds", check.timeoutSeconds());
        switch (check) {
            case DeploymentHealthCheck.Http http -> {
                facts.put("kind", "HTTP");
                facts.put("endpoint", http.endpoint().toString());
                facts.put("expectedStatus", http.expectedStatus());
            }
            case DeploymentHealthCheck.Tcp tcp -> {
                facts.put("kind", "TCP");
                facts.put("port", tcp.port());
                facts.put("stabilitySeconds", tcp.stabilitySeconds());
            }
            case DeploymentHealthCheck.Process process -> {
                facts.put("kind", "PROCESS");
                facts.put("stabilitySeconds", process.stabilitySeconds());
            }
            case DeploymentHealthCheck.Command command -> {
                facts.put("kind", "COMMAND");
                facts.put("command", command.command());
                facts.put("expectedOutput", command.expectedOutput());
            }
            case DeploymentHealthCheck.Udp udp -> {
                facts.put("kind", "UDP");
                facts.put("port", udp.port());
                facts.put("requestHex", udp.requestHex());
                facts.put("responseHex", udp.responseHex());
                udp.probe().ifPresent(value -> facts.put("probeCommand", value));
            }
        }
        return Collections.unmodifiableMap(facts);
    }

    private static Map<String, Object> statusFacts(DeploymentOriginalPublishStatus status) {
        return Map.ofEntries(Map.entry("current", status.current().orElse("none")), Map.entry("currentKind", status.currentKind()),
            Map.entry("currentMode", status.currentMode()), Map.entry("snapshot", status.snapshot()), Map.entry("previousDigest", status.previousDigest().orElse("none")),
            Map.entry("previousKind", status.previousKind()), Map.entry("previousRunning", status.previousRunning().<Object>map(value -> value).orElse("none")),
            Map.entry("maintenance", status.maintenance()), Map.entry("candidate", status.candidate() ? "owned" : "absent"),
            Map.entry("buildActive", status.buildActive()), Map.entry("unitPresent", status.unitPresent()),
            Map.entry("containerPresent", status.containerPresent().<Object>map(value -> value).orElse("not-applicable")), Map.entry("retainedCount", status.retainedCount()));
    }

    private List<Map<String, Object>> eventReferences(List<DeploymentAgentActionLog.Event> events) {
        var types = Set.of("TARGET_PROFILE", "REMOTE_TARGET", "SOURCE", "CANDIDATE", "CURRENT_SOURCE", "SEALED_ARTIFACT", "SEALED_TOOLS",
            "AI_PUBLICATION", "AI_PUBLICATION_VERIFIED", "AI_PUBLICATION_FINISHED", "DELIVERY_EXECUTING", "DELIVERY_RESULT", "RECOVERY_REVIEW_BOUND",
            "HEALTH_REVIEW_DENIED", "HEALTH_REVIEW_REQUEST", "HEALTH_STATUS_QUERY_DENIED", "RUNTIME_REVIEW_DENIED", "RUNTIME_REVIEW_REQUEST");
        return events.stream().filter(value -> types.contains(value.type())).map(value -> Map.<String, Object>of("id", value.id(),
            "type", value.type(), "action", value.action(), "detail", value.detail())).toList();
    }

    private Set<Long> queryEvents() {
        return Set.copyOf(observations.values().stream().map(Observation::event).toList());
    }

    private static String operation(ActionIntent intent) {
        return intent.semantics().map(ActionSemantics::operation).orElse("");
    }

    private static Optional<String> optional(String value) {
        return value.equals("none") ? Optional.empty() : Optional.of(value);
    }

    private static String backend(Selection selected) {
        return selected.part().reviewedRuntime() instanceof DeploymentRuntimeDefinition.Container ? "container" : "native";
    }

    private static String engine(Selection selected) {
        return selected.part().reviewedRuntime() instanceof DeploymentRuntimeDefinition.Container container ? container.engine().name().toLowerCase(Locale.ROOT) : "none";
    }

    private static String unique(List<DeploymentAgentActionLog.Event> events, String type) {
        return events.stream().filter(value -> value.type().equals(type)).map(DeploymentAgentActionLog.Event::detail).distinct().reduce((left, right) -> {
            throw new IllegalStateException("Conflicting original " + type);
        }).orElseThrow();
    }

    private static String originalArchive(List<DeploymentAgentActionLog.Event> events) {
        return events.stream().filter(value -> value.type().equals("SOURCE")).map(DeploymentAgentActionLog.Event::action).distinct().reduce((left, right) -> {
            throw new IllegalStateException("Conflicting original source archives");
        }).orElseThrow();
    }

    private static String latest(List<DeploymentAgentActionLog.Event> events, String type, String component) {
        return events.stream().filter(value -> value.type().equals(type) && value.action().equals(component)).reduce((left, right) -> right).orElseThrow().detail();
    }

    private static Map<String, Object> requestFacts(ActionIntent intent, CommandRequest request) {
        return Map.of("exactAction", intent.exactAction(), "operation", operation(intent), "serverTargets", intent.serverIds(), "resourceTargets", intent.semantics().orElseThrow().targets(), "businessRevision", intent.revision(), "timeout", request.timeout().toString(), "outputLimitBytes", request.outputLimitBytes(), "stdinBytes", request.stdin().length);
    }

    private static String digest(Map<String, ?> facts) throws Exception {
        return DeploymentAgentInputs.hash(DeploymentAiJson.facts(facts, 1048576));
    }

    private ApiException unconfirmed(String event, Exception cause) {
        var error = new ApiException(new Problem(ProblemCode.UNKNOWN, event, "Original Agent health evidence is unconfirmed", Map.of("task", task.id().toString()), Optional.empty()));
        if (cause != null) error.initCause(cause);
        return error;
    }

    @Override
    public RemoteFacts facts() {
        return borrowed.facts();
    }

    @Override
    public OperationResult<TransferResult> upload(TransferRequest request, ApprovalGrant grant) throws ApiException {
        return borrowed.upload(request, grant);
    }

    @Override
    public OperationResult<TransferResult> upload(TransferRequest request, ApprovalGrant grant, TransferConflictChoice choice) throws ApiException {
        return borrowed.upload(request, grant, choice);
    }

    @Override
    public OperationResult<TransferResult> download(TransferRequest request, ApprovalGrant grant) throws ApiException {
        return borrowed.download(request, grant);
    }

    @Override
    public OperationResult<TransferResult> download(TransferRequest request, ApprovalGrant grant, TransferConflictChoice choice) throws ApiException {
        return borrowed.download(request, grant, choice);
    }

    @Override
    public OperationResult<TransferResult> resumeTransfer(UUID transferId, ApprovalGrant grant) throws ApiException {
        return borrowed.resumeTransfer(transferId, grant);
    }

    @Override
    public OperationResult<Void> discardTransfer(UUID transferId, ApprovalGrant grant) throws ApiException {
        return borrowed.discardTransfer(transferId, grant);
    }

    @Override
    public void close() {
        stopped = true;
        query = null;
        pending = null;
        observations.clear();
    }
}
