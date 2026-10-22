package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;

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
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildCommandChannel;
import gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner;
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.linux.publish.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentProjectChannel;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentGraphPublisher;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentPublishResult;

/** Agent-only, invocation-local observations for one exact cleanup review and its absence check. */
public final class DeploymentAgentCleanupEvidence implements DeploymentCurrentCommandEvidence, RemoteSession {
    private static final String OPERATION = "deployment.publication.candidate-cleanup.v1";

    private final ModContext original;

    private final RemoteSession borrowed;

    private final ServerId server;

    private final TaskContext task;

    private final String revision;

    private final DeploymentRemoteLayout layout;

    private final DeploymentPublishResult proposal;

    private final Map<String, DeploymentGraphPublisher.Candidate> candidates;

    private final DeploymentAgentActionLog journal;

    private final DeploymentSendGuard guard;

    private final boolean successful;

    private final DeploymentAgentActionLog.Identity identity;

    private final String profile;

    private final DeploymentCommandReviewScope reviewed;

    private final DeploymentExecutionStore executions;

    private final DeploymentExecutionIdentity executionIdentity;

    private Capture capture;

    private Selection observing;

    private String observingCleanupAction;

    private Pending pending;

    private boolean refused;

    private record Selection(DeploymentApplicationGraph.Part part, DeploymentUnitCommit commit,
                             DeploymentGraphPublisher.Candidate candidate, DeploymentRemoteWorkspace workspace) {
    }

    private record Capture(ActionIntent intent, Map<String, Object> request, OperationResult<CommandResult> result) {
    }

    private record Pending(ActionIntent intent, Selection selected, String evidenceDigest, String reviewDigest,
                           long queryEvent, boolean approved) {
        Pending approve() {
            return new Pending(intent, selected, evidenceDigest, reviewDigest, queryEvent, true);
        }
    }

    public DeploymentAgentCleanupEvidence(ModContext original, RemoteSession borrowed, ServerId server,
                                          TaskContext task, String revision, DeploymentPublishResult proposal,
                                          Map<String, DeploymentGraphPublisher.Candidate> candidates,
                                          DeploymentAgentActionLog journal, DeploymentSendGuard guard,
                                          boolean successful) throws Exception {
        this.original = Objects.requireNonNull(original);
        this.borrowed = Objects.requireNonNull(borrowed);
        this.server = Objects.requireNonNull(server);
        this.task = Objects.requireNonNull(task);
        this.revision = Objects.requireNonNull(revision);
        this.proposal = Objects.requireNonNull(proposal);
        this.layout = proposal.graph().components().getFirst().unit().layout();
        if (proposal.graph().components().stream().anyMatch(part -> !layout.equals(part.unit().layout())))
            throw new IllegalArgumentException("Original Agent graph has conflicting directory identities");
        this.candidates = Map.copyOf(candidates);
        this.journal = Objects.requireNonNull(journal);
        this.guard = Objects.requireNonNull(guard);
        this.successful = successful;
        executions = new DeploymentExecutionStore(original.db());
        executionIdentity = executions.findForTask(task).orElseThrow().identity();
        identity = journal.find(task).orElseThrow().identity();
        profile = unique(journal.events(task), "TARGET_PROFILE", null);
        reviewed = original instanceof DeploymentCommandReviewScope scope ? scope.withCurrentEvidence(this)
            : DeploymentAgentApproval.scope(original, task, identity.approvalMode(),
            identity.rememberAuthorization(), () -> revision, this);
        requireBinding();
    }

    public ModContext context() {
        return reviewed;
    }

    @Override
    public Map<String, ?> facts(ActionIntent intent, CommandRequest request, TaskContext supplied) throws ApiException {
        if (!cleanup(intent)) return Map.of();
        try {
            if (refused || supplied != task) throw new IllegalStateException("Cleanup review cannot be replayed");
            pending = null;
            requireBinding();
            var events = journal.events(task);
            var selected = select(intent, request, events);
            capture = null;
            var part = selected.part();
            String backend = part.reviewedRuntime() instanceof DeploymentRuntimeDefinition.Container ? "container" : "native";
            String engine = part.reviewedRuntime() instanceof DeploymentRuntimeDefinition.Container container
                ? container.engine().name().toLowerCase(Locale.ROOT) : "none";
            var channel = new DeploymentPublishChannel(this, reviewed.interaction(), server, task,
                selected.candidate().artifactSha256(), this::requireBinding, layout);
            observing = selected;
            observingCleanupAction = DeploymentAgentInputs.hash(intent.exactAction());
            var observed = channel.originalStatus(part.unit(), selected.candidate().candidateId(),
                Optional.of(selected.commit().release().sha256()), backend, engine);
            observing = null;
            observingCleanupAction = null;
            var current = capture;
            capture = null;
            if (observed.outcome() != TaskResult.SUCCEEDED || observed.value().isEmpty() || current == null
                || current.result().outcome() != TaskResult.SUCCEEDED || current.result().value().isEmpty())
                throw new IllegalStateException("Current original status is unconfirmed");
            var receipt = current.result().value().orElseThrow();
            var status = observed.value().orElseThrow();
            if (receipt.truncated() || receipt.exitCode().filter(value -> value == 0).isEmpty() || status.buildActive()
                || successful && !status.current().equals(Optional.of(selected.commit().release().sha256())))
                throw new IllegalStateException("Current cleanup boundary is unconfirmed");
            requireBinding();
            String queryDigest = DeploymentAgentInputs.hash(current.intent().exactAction());
            String receiptDigest = digest(Map.of("outcome", current.result().outcome().name(), "exitCode", receipt.exitCode().orElseThrow(),
                "stdout", receipt.stdout(), "stderr", receipt.stderr(), "truncated", receipt.truncated()));
            journal.event(task, "CLEANUP_STATUS_QUERY_RESULT", queryDigest, receiptDigest);
            long queryEvent = journal.events(task).getLast().id();
            var evidence = new LinkedHashMap<String, Object>();
            evidence.put("schema", "agent-current-cleanup-evidence.v1");
            evidence.put("scope", "CURRENT_APPROVAL_CALL_ONLY; historical events are references, not current observations or permissions");
            evidence.put("currentTask", task.id().toString());
            evidence.put("originalTask", task.id().toString());
            evidence.put("cleanupExactActionSha256", DeploymentAgentInputs.hash(intent.exactAction()));
            evidence.put("server", server.id());
            evidence.put("targetProfileSha256", profile);
            evidence.put("authenticatedTargetSha256", identity.targetDigest());
            evidence.put("originalConfigurationRevision", identity.configurationDigest());
            evidence.put("currentReviewConfigurationRevision", revision);
            evidence.put("component", part.componentId());
            evidence.put("unit", part.unit().id());
            evidence.put("releaseRoot", part.unit().releaseRoot());
            evidence.put("ownershipManifestSha256", part.unit().ownershipManifestSha256());
            evidence.put("candidate", selected.candidate().candidateId());
            evidence.put("candidateRoot", layout.candidateRoot(selected.candidate().candidateId()));
            evidence.put("sourceArchiveSha256", selected.workspace().archiveSha256());
            evidence.put("originalSourceRevision", unique(events, "SOURCE", null));
            evidence.put("sealedSourceRevision", selected.candidate().sourceRevision());
            evidence.put("sealedArtifactSha256", selected.candidate().artifactSha256());
            evidence.put("releaseSha256", selected.commit().release().sha256());
            evidence.put("runtimeConfigurationSha256", selected.commit().configuration().sha256());
            evidence.put("proposalSha256", DeploymentAgentInputs.hash(proposal.toString()));
            evidence.put("originalExecution", executionIdentity.executionId().toString());
            evidence.put("originalReviewedRevision", executionIdentity.reviewedRevision());
            evidence.put("originalRemoteLayout", Map.of("canonical", layout.canonical(), "sha256", layout.digest()));
            evidence.put("helperSha256", DeploymentManagedHelperBundle.helperSha256(layout));
            evidence.put("helperProtocol", DeploymentManagedHelperBundle.PROTOCOL_VERSION);
            evidence.put("publicationVerified", successful);
            evidence.put("outerDeliveryOutcome", DeploymentAgentActionLog.effectsSettled(events) ? "SETTLED" : "UNCONFIRMED_WRAPPER_RESULT; no delivery result is synthesized");
            evidence.put("observationLimits", "The live helper status checks original candidate task ownership, build inactivity and owned CURRENT paths. Sealed source/artifact identities are retained ledger facts, not fresh remote hashes. Release identity also binds source/configuration/runtime/resources and need not equal artifact identity. No claim that every unpublished or unused artifact is absent.");
            evidence.put("queryEvent", queryEvent);
            evidence.put("queryRequest", current.request());
            evidence.put("queryResult", Map.of("outcome", current.result().outcome().name(), "exitCode", receipt.exitCode().orElseThrow(),
                "stdout", receipt.stdout(), "stderr", receipt.stderr(), "truncated", receipt.truncated()));
            evidence.put("parsedStatus", statusFacts(status));
            evidence.put("originalEventReferences", references(events, part.componentId()));
            String evidenceDigest = digest(evidence);
            String reviewDigest = digest(Map.of("command", requestFacts(intent, request), "currentEvidenceSha256", evidenceDigest));
            evidence.put("evidenceSha256", evidenceDigest);
            evidence.put("reviewRequestSha256", reviewDigest);
            pending = new Pending(intent, selected, evidenceDigest, reviewDigest, queryEvent, false);
            journal.event(task, "CLEANUP_REVIEW_REQUEST", reviewDigest,
                "exactAction=" + DeploymentAgentInputs.hash(intent.exactAction()) + ";evidence=" + evidenceDigest + ";queryEvent=" + queryEvent);
            return Collections.unmodifiableMap(evidence);
        } catch (Exception unavailable) {
            capture = null;
            observing = null;
            observingCleanupAction = null;
            pending = null;
            if (unavailable instanceof ApiException refusal && refusal.problem().eventCode().equals("DEPLOYMENT_COMMAND_REVIEW_DENIED"))
                throw refusal;
            throw unconfirmed("DEPLOYMENT_AGENT_CLEANUP_EVIDENCE_UNCONFIRMED", unavailable);
        }
    }

    @Override
    public void reviewed(ActionIntent intent, CommandRequest request, TaskContext supplied,
                         DeploymentReviewDecision decision) throws ApiException {
        if (!cleanup(intent)) {
            if (observing != null && intent.semantics().filter(value -> value.operation().equals("deployment.publication.original-status.v1")).isPresent()
                && decision.decision().equals("DENY")) {
                refused = true;
                try {
                    journal.event(task, "CLEANUP_STATUS_QUERY_DENIED", DeploymentAgentInputs.hash(intent.exactAction()),
                        "cleanupExactAction=" + observingCleanupAction + ";reasonSha256=" + DeploymentAgentInputs.hash(decision.reason()));
                } catch (Exception unavailable) {
                    throw unconfirmed("DEPLOYMENT_AGENT_CLEANUP_REVIEW_UNCONFIRMED", unavailable);
                }
            }
            return;
        }
        boolean denial = decision.decision().equals("DENY");
        if (denial) refused = true;
        try {
            verify(intent, request, supplied);
            journal.event(task, decision.decision().equals("APPROVE") ? "CLEANUP_REVIEW_APPROVED" : "CLEANUP_REVIEW_DENIED",
                pending.reviewDigest(), "exactAction=" + DeploymentAgentInputs.hash(intent.exactAction())
                    + ";evidence=" + pending.evidenceDigest() + ";queryEvent=" + pending.queryEvent()
                    + ";reasonSha256=" + DeploymentAgentInputs.hash(decision.reason()));
            if (decision.decision().equals("APPROVE")) pending = pending.approve();
        } catch (Exception unavailable) {
            throw unconfirmed("DEPLOYMENT_AGENT_CLEANUP_REVIEW_UNCONFIRMED", unavailable);
        } finally {
            if (denial) pending = null;
        }
    }

    @Override
    public void verify(ActionIntent intent, CommandRequest request, TaskContext supplied) throws ApiException {
        if (!cleanup(intent)) return;
        if (supplied != task || pending == null || !pending.intent().equals(intent)
            || !RemoteActions.command(server, intent.revision(), request).exactAction().equals(intent.exactAction()))
            throw unconfirmed("DEPLOYMENT_AGENT_CLEANUP_REQUEST_CHANGED", null);
        requireBinding();
        try {
            if (!select(intent, request, journal.events(task)).equals(pending.selected()))
                throw new IllegalStateException("Current selected cleanup binding changed");
        } catch (Exception changed) {
            throw unconfirmed("DEPLOYMENT_AGENT_CLEANUP_CANDIDATE_CHANGED", changed);
        }
    }

    private static boolean cleanup(ActionIntent intent) {
        return intent.semantics().filter(value -> value.operation().equals(OPERATION)).isPresent();
    }

    private Selection select(ActionIntent intent, CommandRequest request, List<DeploymentAgentActionLog.Event> events) throws Exception {
        for (var part : proposal.graph().components()) {
            var candidate = candidates.get(part.componentId());
            if (candidate == null) throw new IllegalStateException("Original candidate missing");
            var commit = proposal.units().stream().filter(value -> value.unit().equals(part.unit())).findFirst().orElseThrow();
            String archive = events.stream().filter(value -> value.type().equals("SOURCE")).map(DeploymentAgentActionLog.Event::action).distinct()
                .reduce((left, right) -> {
                    throw new IllegalStateException("Conflicting source archives");
                }).orElseThrow();
            var workspace = gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentWorkspaceBinding.workspace(task.id(), journal.find(task).orElseThrow(), events, part.unit().id(), archive, layout);
            String script = List.of(layout.helperPath(), "candidate-cleanup-task", part.unit().id(),
                    candidate.candidateId(), task.id().toString()).stream().map(DeploymentBuildCommandChannel::shellQuote)
                .collect(java.util.stream.Collectors.joining(" "));
            try (var expected = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(script, layout), new byte[0], Duration.ofSeconds(90), 4096)) {
                var actual = RemoteActions.command(server, candidate.artifactSha256(), expected).withSemantics(new ActionSemantics(OPERATION,
                    Set.of(new ActionTarget(server.id(), "deployment-release", part.unit().releaseRoot()),
                        new ActionTarget(server.id(), "deployment-candidate", layout.candidateRoot(candidate.candidateId())))));
                if (!actual.equals(intent) || !request.script().equals(expected.script())
                    || !request.timeout().equals(expected.timeout()) || request.outputLimitBytes() != 4096 || request.stdin().length != 0)
                    continue;
                if (!workspace.candidateId().equals(candidate.candidateId()) || !part.unit().server().equals(server)
                    || !latest(events, "CANDIDATE", part.componentId()).equals(candidate.candidateId())
                    || !latest(events, "CURRENT_SOURCE", part.componentId()).equals(candidate.sourceRevision())
                    || !latest(events, "SEALED_ARTIFACT", part.componentId()).equals(candidate.artifactSha256())
                    || !latest(events, "SEALED_TOOLS", part.componentId()).equals(candidate.toolchainBindingSha256() + ":" + candidate.hasTools()))
                    throw new IllegalStateException("Original sealed candidate changed");
                return new Selection(part, commit, candidate, workspace);
            }
        }
        throw new IllegalArgumentException("Cleanup is not the complete original fixed request");
    }

    private void requireBinding() throws ApiException {
        try {
            guard.verify();
            var saved = journal.find(task).orElseThrow();
            if (!gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentLayouts.original(original, task, saved.identity(), journal.events(task)).equals(layout))
                throw new IllegalStateException("Original Agent cleanup layout changed");
            if (!identity.equals(saved.identity()) || !Set.of(4, 5).contains(saved.executionSemantics()) || !identity.server().equals(server.id())
                || !profile.equals(DeploymentAgentInputs.targetProfile(original.servers().get(server, task)))
                || !identity.targetDigest().equals(DeploymentAgentInputs.targetDigest(server, borrowed)))
                throw new IllegalStateException("Original cleanup identity changed");
            DeploymentFixedCommandApproval.requireRoute(original, task, revision);
            var events = journal.events(task);
            gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentWorkspaceBinding.find(task.id(), saved, events);
            var execution = executions.findForTask(task).orElseThrow();
            var retained = executions.publicationTemplate(execution).orElseThrow();
            if (!execution.identity().equals(executionIdentity) || executionIdentity.mode() != DeploymentExecutionIdentity.DeploymentExecutionMode.AGENT
                || !executionIdentity.taskId().equals(task.id()) || !executionIdentity.server().equals(server)
                || !executionIdentity.applicationId().equals(proposal.graph().applicationId())
                || !retained.graph().equals(proposal.graph()) || !retained.units().equals(proposal.units()))
                throw new IllegalStateException("Original formal proposal changed");
            var commands = original.tasks().findCheckpoint(task, DeploymentModelCommandRunner.KEY);
            if (gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainProgramState.hasUnconfirmed(
                original.tasks(), task, server.id(), layout, events)
                || !settledMutations(events)
                || commands.isPresent() && !commands.orElseThrow().snapshot().released()
                || successful && (!events.stream().anyMatch(value -> value.type().equals("AI_PUBLICATION_FINISHED"))
                || !events.stream().anyMatch(value -> value.type().equals("AI_PUBLICATION_VERIFIED"))))
                throw new IllegalStateException("Original modifying effects are unsettled");
        } catch (Exception changed) {
            throw unconfirmed("DEPLOYMENT_AGENT_CLEANUP_BINDING_CHANGED", changed);
        }
    }

    /** An unfinished outer delivery can contain this proven finish; it is never declared settled here. */
    private boolean settledMutations(List<DeploymentAgentActionLog.Event> events) {
        if (DeploymentAgentActionLog.effectsSettled(events)) return true;
        if (!successful || !DeploymentAgentActionLog.effectsSettled(events.stream()
            .filter(value -> !value.type().startsWith("DELIVERY_")).toList())) return false;
        var pendingDelivery = new ArrayList<DeploymentAgentActionLog.Event>();
        for (var event : events) {
            if (event.type().equals("DELIVERY_EXECUTING")) pendingDelivery.add(event);
            if (event.type().equals("DELIVERY_RESULT")) {
                var prior = pendingDelivery.stream().filter(value -> value.action().equals(event.action())).findFirst();
                prior.ifPresent(pendingDelivery::remove);
            }
        }
        if (pendingDelivery.size() != 1) return false;
        var delivery = pendingDelivery.getFirst();
        var publication = events.stream().filter(value -> value.type().equals("AI_PUBLICATION")
            && value.action().equals(proposal.graph().applicationId()) && value.id() > delivery.id()).toList();
        var verified = events.stream().filter(value -> value.type().equals("AI_PUBLICATION_VERIFIED")
            && value.action().equals(proposal.graph().applicationId())).reduce((left, right) -> right);
        var finished = events.stream().filter(value -> value.type().equals("AI_PUBLICATION_FINISHED")
            && value.action().equals(proposal.graph().applicationId())).reduce((left, right) -> right);
        return delivery.action().matches("[0-9a-f]{64}") && delivery.detail().matches("[0-9]{1,10}")
            && publication.size() == 1 && verified.isPresent() && finished.isPresent()
            && publication.getFirst().id() < verified.orElseThrow().id() && verified.orElseThrow().id() < finished.orElseThrow().id();
    }

    private static String unique(List<DeploymentAgentActionLog.Event> events, String type, String action) {
        var values = events.stream().filter(value -> value.type().equals(type) && (action == null || value.action().equals(action)))
            .map(DeploymentAgentActionLog.Event::detail).distinct().toList();
        if (values.size() != 1) throw new IllegalStateException("Original evidence is missing or conflicting");
        return values.getFirst();
    }

    private static String latest(List<DeploymentAgentActionLog.Event> events, String type, String action) {
        return events.stream().filter(value -> value.type().equals(type) && value.action().equals(action)).reduce((left, right) -> right).orElseThrow().detail();
    }

    private static List<Map<String, Object>> references(List<DeploymentAgentActionLog.Event> events, String component) {
        var global = Set.of("TARGET_PROFILE", "REMOTE_TARGET", "SOURCE", "AI_PUBLICATION", "AI_PUBLICATION_VERIFIED", "AI_PUBLICATION_FINISHED",
            "RECOVERY_REVIEW_BOUND", "GRAPH_HEALTH_RESULT", "GRAPH_REMOTE_CLEANUP_RESULT", "CLEANUP_REVIEW_DENIED", "CLEANUP_REVIEW_REQUEST",
            "DELIVERY_EXECUTING", "DELIVERY_RESULT", "CLEANUP_STATUS_QUERY_DENIED");
        var local = Set.of("CANDIDATE", "CURRENT_SOURCE", "SEALED_ARTIFACT", "SEALED_TOOLS", "CANDIDATE_CLEANUP_EXECUTING", "CANDIDATE_CLEANUP_RESULT");
        return events.stream().filter(value -> global.contains(value.type()) || value.action().equals(component) && local.contains(value.type()))
            .map(value -> Map.<String, Object>of("id", value.id(), "type", value.type(), "action", value.action(), "detail", value.detail())).toList();
    }

    private static Map<String, Object> statusFacts(DeploymentOriginalPublishStatus status) {
        return Map.ofEntries(Map.entry("current", status.current().orElse("none")), Map.entry("currentKind", status.currentKind()),
            Map.entry("currentMode", status.currentMode()), Map.entry("snapshot", status.snapshot()),
            Map.entry("previousDigest", status.previousDigest().orElse("none")), Map.entry("previousKind", status.previousKind()),
            Map.entry("previousRunning", status.previousRunning().<Object>map(value -> value).orElse("none")),
            Map.entry("maintenance", status.maintenance()), Map.entry("candidate", status.candidate() ? "owned" : "absent"),
            Map.entry("buildActive", status.buildActive()), Map.entry("unitPresent", status.unitPresent()),
            Map.entry("containerPresent", status.containerPresent().<Object>map(value -> value).orElse("not-applicable")),
            Map.entry("retainedCount", status.retainedCount()));
    }

    private static Map<String, Object> requestFacts(ActionIntent intent, CommandRequest request) {
        return Map.of("exactAction", intent.exactAction(), "operation", intent.semantics().orElseThrow().operation(),
            "serverTargets", intent.serverIds(), "resourceTargets", intent.semantics().orElseThrow().targets().stream()
                .sorted(Comparator.comparing(ActionTarget::serverId).thenComparing(ActionTarget::kind).thenComparing(ActionTarget::id)).toList(),
            "businessRevision", intent.revision(), "timeout", request.timeout().toString(),
            "outputLimitBytes", request.outputLimitBytes(), "stdinBytes", request.stdin().length);
    }

    private static String digest(Map<String, ?> facts) throws Exception {
        return DeploymentAgentInputs.hash(DeploymentAiJson.facts(facts, 1048576));
    }

    @Override
    public OperationResult<CommandResult> execute(CommandRequest request, ApprovalGrant grant) throws ApiException {
        requireBinding();
        var selected = pending;
        boolean deleting = selected != null && RemoteActions.command(server, selected.intent().revision(), request).exactAction()
            .equals(selected.intent().exactAction());
        if (deleting) {
            if (!selected.approved()) throw unconfirmed("DEPLOYMENT_AGENT_CLEANUP_REVIEW_MISSING", null);
            verify(selected.intent(), request, task);
            pending = null;
        }
        if (!deleting && request.script().contains("'candidate-cleanup-task'"))
            throw unconfirmed("DEPLOYMENT_AGENT_CLEANUP_REQUEST_CHANGED", null);
        var result = borrowed.execute(request, grant);
        if (observing != null) {
            if (capture != null) throw unconfirmed("DEPLOYMENT_AGENT_CLEANUP_QUERY_DUPLICATED", null);
            var candidate = observing.part();
            var intent = RemoteActions.command(server, observing.candidate().artifactSha256(), request)
                .withSemantics(new ActionSemantics("deployment.publication.original-status.v1",
                    Set.of(new ActionTarget(server.id(), "deployment-release", candidate.unit().releaseRoot()),
                        new ActionTarget(server.id(), "deployment-candidate", layout.candidateRoot(observing.candidate().candidateId())),
                        new ActionTarget(server.id(), "deployment-snapshot", layout.snapshotRoot(candidate.unit().id(), task.id())),
                        new ActionTarget(server.id(), "deployment-application-locks", layout.applicationLockRoot(candidate.unit().id())))));
            capture = new Capture(intent, requestFacts(intent, request), result);
        }
        if (!deleting) return result;
        try {
            if (result.outcome() != TaskResult.SUCCEEDED || result.value().filter(value -> !value.truncated()
                && value.exitCode().filter(code -> code == 0).isPresent() && value.stdout().equals("CANDIDATE_CLEANED=1\n")).isEmpty())
                return result;
            requireBinding();
            var projects = new DeploymentAgentProjectChannel(this, reviewed.interaction(), server, task, revision, () -> revision, layout);
            var absent = projects.candidateStatus(selected.selected().workspace());
            if (absent.present() || absent.buildActive())
                throw new IllegalStateException("Deleted candidate absence is unconfirmed");
            journal.event(task, "CLEANUP_ABSENCE_VERIFIED", selected.reviewDigest(), selected.selected().candidate().candidateId());
            return result;
        } catch (Exception unavailable) {
            return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(unconfirmed("DEPLOYMENT_AGENT_CLEANUP_ABSENCE_UNCONFIRMED", unavailable).problem()));
        }
    }

    private ApiException unconfirmed(String event, Exception cause) {
        var error = new ApiException(new Problem(ProblemCode.UNKNOWN, event, "Original Agent cleanup evidence is unconfirmed",
            Map.of("task", task.id().toString()), Optional.empty()));
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
    public void close() { /* Borrowed session remains owned by the original workflow. */ }
}
