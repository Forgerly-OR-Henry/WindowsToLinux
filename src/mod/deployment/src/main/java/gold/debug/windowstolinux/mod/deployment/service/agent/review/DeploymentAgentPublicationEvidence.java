package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.review.*;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildCommandChannel;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperReviewContract;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentPublishArguments;
import gold.debug.windowstolinux.mod.deployment.linux.runtime.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentLayouts;
import gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentWorkspaceBinding;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentGraphPublisher;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentPublishResult;

/** Invocation-local identities from the actual original publication objects; never a verdict or remote observation. */
public final class DeploymentAgentPublicationEvidence implements DeploymentCurrentCommandEvidence {
    private static final String PUBLISH = "deployment.publication.publish.v1";

    private final DeploymentCommandReviewScope original;
    private final RemoteSession session;
    private final ServerId server;
    private final TaskContext task;
    private final String configuration;
    private final DeploymentRemoteLayout layout;
    private final DeploymentAgentActionLog journal;
    private final DeploymentAgentActionLog.Snapshot snapshot;
    private final DeploymentAgentActionLog.Event source;
    private final String profile;
    private final Map<String, Selection> selections;
    private Pending pending;
    private boolean stopped;

    private record Selection(DeploymentApplicationGraph.Part part, DeploymentUnitCommit commit,
                             DeploymentGraphPublisher.Candidate candidate, DeploymentRemoteWorkspace workspace,
                             Map<String, DeploymentAgentActionLog.Event> receipts) {
    }

    private record Pending(ActionIntent intent, String component) {
    }

    public DeploymentAgentPublicationEvidence(DeploymentCommandReviewScope original, RemoteSession session, ServerId server,
                                                TaskContext task, String configuration, DeploymentPublishResult proposal,
                                                Map<String, DeploymentGraphPublisher.Candidate> candidates,
                                                Map<String, DeploymentRemoteWorkspace> workspaces,
                                                DeploymentAgentActionLog journal) throws Exception {
        this.original = Objects.requireNonNull(original);
        this.session = Objects.requireNonNull(session);
        this.server = Objects.requireNonNull(server);
        this.task = Objects.requireNonNull(task);
        this.configuration = Objects.requireNonNull(configuration);
        this.journal = Objects.requireNonNull(journal);
        layout = proposal.graph().components().getFirst().unit().layout();
        snapshot = journal.find(task).orElseThrow();
        var events = journal.events(task);
        source = originalSource(events);
        profile = profile(events);
        var selected = new LinkedHashMap<String, Selection>();
        for (var part : proposal.graph().components()) {
            var commit = proposal.units().stream().filter(value -> value.unit().equals(part.unit())).findFirst().orElseThrow();
            var candidate = Objects.requireNonNull(candidates.get(part.componentId()));
            var workspace = Objects.requireNonNull(workspaces.get(part.componentId()));
            require(part.unit().server().equals(server) && part.unit().layout().equals(layout)
                && part.unit().equals(DeploymentUnitIdentity.forManaged(part.unit().id(), server, part.unit().ownershipManifestSha256(), layout))
                && commit.release().unitId().equals(part.unit().id()), "Publication unit changed");
            selected.put(part.componentId(), new Selection(part, commit, candidate, workspace, receipts(events, part, candidate)));
        }
        require(selected.keySet().equals(candidates.keySet()) && selected.keySet().equals(workspaces.keySet())
            && proposal.units().size() == selected.size(), "Publication graph changed");
        selections = Map.copyOf(selected);
        requireBinding();
    }

    @Override
    public Map<String, ?> facts(ActionIntent intent, CommandRequest request, TaskContext supplied) throws ApiException {
        pending = null;
        if (!covered(intent)) return Map.of();
        try {
            require(supplied == task, "Foreign publication task");
            requireBinding();
            var selected = select(intent, request);
            pending = new Pending(intent, selected.part().componentId());
            var unit = selected.part().unit();
            var candidate = selected.candidate();
            var facts = new LinkedHashMap<String, Object>();
            facts.put("phase", "PRE_EXECUTION_PUBLICATION_IDENTITY");
            facts.put("authority", "NONE");
            facts.put("task", task.id().toString());
            facts.put("server", server.id());
            facts.put("component", selected.part().componentId());
            facts.put("candidateRoot", selected.workspace().candidateRoot());
            facts.put("layoutSha256", layout.digest());
            facts.put("exactActionSha256", DeploymentAgentInputs.hash(intent.exactAction()));
            facts.put("operation", PUBLISH);
            facts.put("resourceTarget", Map.of("kind", "deployment-release", "id", unit.releaseRoot()));
            facts.put("positionalArguments", List.of(argument(1, "applicationId", unit.id()),
                argument(2, "candidateId", candidate.candidateId()), argument(3, "releaseSha256", selected.commit().release().sha256()),
                argument(4, "expectedOwnershipManifestSha256", unit.ownershipManifestSha256())));
            facts.put("ownerMeaning", "Expected owner value in this original proposal, whether newly proposed or reused; not an observation that the remote owner exists or matches");
            facts.put("businessRevisionRole", "The selected sealed artifact is used as this publication command's businessRevision; equality here is intentional");
            facts.put("sourceArchiveSha256", source.action());
            facts.put("originalSnapshotManifestSha256", source.detail());
            facts.put("sourceEventId", source.id());
            facts.put("currentProtectedTreeRevision", candidate.sourceRevision());
            facts.put("sealedArtifactSha256", candidate.artifactSha256());
            facts.put("toolchainBindingSha256", candidate.toolchainBindingSha256());
            facts.put("configurationSha256", selected.commit().configuration().sha256());
            facts.put("runtimeSha256", DeploymentAgentInputs.hash(selected.part().reviewedRuntime().toString()));
            facts.put("resourcesSha256", DeploymentAgentInputs.hash(selected.part().resources().toString()));
            facts.put("releaseSha256", selected.commit().release().sha256());
            facts.put("releaseDerivation", "SHA256 UTF-8(agent-release: + artifact + : + currentSource + : + configuration + : + original runtime.toString() + : + original resources.toString())");
            facts.put("receiptEventIds", selected.receipts().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, value -> value.getValue().id())));
            facts.put("comparedMetadataRelationships", List.of("command application/candidate/release/owner == this selected original proposal",
                "command config/runtime/resource/tool arguments == this selected original proposal",
                "command businessRevision == selected sealed artifact == SEALED_ARTIFACT.detail",
                "candidate sourceRevision == CURRENT_SOURCE.detail; seal execution/result bind that same revision and artifact",
                "workspace archive == SOURCE.action; SOURCE.detail is a separate snapshot-manifest identity",
                "proposal release == recomputed original Agent release derivation"));
            facts.put("completedLocalChecks", "MATCHED_CURRENT_ORIGINAL_PUBLICATION_OBJECTS_AND_RETAINED_RECEIPTS");
            facts.put("independentAppReview", "NOT_DECIDED_BY_THIS_PROJECTION");
            facts.put("hostSingleUseGrant", "NOT_GRANTED_BY_THIS_PROJECTION");
            facts.put("remoteOwnerAndCurrent", "UNKNOWN_FOR_THIS_PENDING_REQUEST");
            facts.put("publicationResult", "NO_RESULT_FOR_THIS_PENDING_REQUEST");
            facts.put("secretBindingScope", "Only literal reference/revision/digest framing is matched; no secret plaintext is read and this projection does not verify secret contents or replace staging checks");
            facts.put("stdinBytes", request.stdin().length);
            return Map.of("publicationIdentity", Map.copyOf(facts));
        } catch (Exception invalid) {
            throw stop();
        }
    }

    @Override
    public void verify(ActionIntent intent, CommandRequest request, TaskContext supplied) throws ApiException {
        if (!covered(intent)) return;
        try {
            require(supplied == task && pending != null && pending.intent().equals(intent), "Publication review changed");
            requireBinding();
            require(select(intent, request).part().componentId().equals(pending.component()), "Publication component changed");
        } catch (Exception invalid) {
            throw stop();
        }
    }

    /** Called by the original send guard, after its existing checks and before the actual transport. */
    public void verifyPendingBinding() throws ApiException {
        if (pending != null) requireBinding();
    }

    @Override
    public String reviewInstructions() {
        if (pending == null || stopped) return "";
        return "The product-owned currentCommandEvidence.publicationIdentity describes completed local comparisons for this exact pending original publication. "
            + "After publish-deployment/container the first four arguments are application, candidate, derived release and expected ownership manifest. "
            + "The release is derived from the sealed artifact, current source, configuration, runtime and resources; it need not equal the artifact or owner. "
            + "The command businessRevision deliberately equals its selected sealed artifact. Owner is a proposal value, not observed remote ownership. "
            + "SOURCE archive, snapshot manifest and CURRENT_SOURCE protected tree identify different objects. This evidence is not remote success, secret verification, an APP verdict or host authority. "
            + "Review the complete command and remaining staging/ownership/runtime checks independently; APP may still DENY and every accepted action still needs its own host grant.";
    }

    private Selection select(ActionIntent intent, CommandRequest request) {
        require(request.timeout().equals(Duration.ofSeconds(120)) && request.outputLimitBytes() == 4096 && request.stdin().length == 0,
            "Publication request frame changed");
        String body = DeploymentHelperReviewContract.verifiedInvocation(request.script(), layout);
        var words = literalWords(body);
        require(words.size() >= 11 && words.getFirst().equals(layout.helperPath()), "Publication invocation changed");
        var selected = selections.values().stream().filter(value -> value.part().unit().id().equals(words.get(2))).findFirst().orElseThrow();
        var unit = selected.part().unit();
        var candidate = selected.candidate();
        var runtime = selected.part().reviewedRuntime();
        String verb = runtime instanceof DeploymentRuntimeDefinition.Container ? "publish-container" : "publish-deployment";
        require(words.get(1).equals(verb), "Publication backend changed");
        int count = canonicalCount(words.get(10));
        require(count == selected.commit().secrets().size() && count <= (words.size() - 11) / 3, "Publication secret references changed");
        var secrets = new LinkedHashMap<DeploymentSecretRef, String>();
        for (int index = 0; index < count; index++) {
            int offset = 11 + index * 3;
            String revision = words.get(offset + 1), digest = words.get(offset + 2);
            require(revision.matches("[1-9][0-9]*") && digest.matches("[0-9a-f]{64}"), "Publication secret framing changed");
            var reference = new DeploymentSecretRef(words.get(offset), Long.parseLong(revision));
            require(secrets.put(reference, digest) == null, "Duplicate publication secret reference");
        }
        require(secrets.keySet().equals(Set.copyOf(selected.commit().secrets())), "Publication secret references changed");
        var expected = new ArrayList<>(List.of(layout.helperPath(), verb, unit.id(), candidate.candidateId(), selected.commit().release().sha256(),
            unit.ownershipManifestSha256(), "identity-v2", runtime.identityPolicy().name(), DeploymentWorkloadArguments.payload(runtime)));
        expected.addAll(DeploymentPublishArguments.inputs(selected.commit().configuration().sha256(), secrets));
        expected.addAll(DeploymentPublishArguments.content(unit.id(), selected.part().resources(), layout));
        if (candidate.hasTools()) expected.addAll(List.of("tools-v1", candidate.toolchainBindingSha256()));
        if (runtime instanceof DeploymentRuntimeDefinition.Container container) expected.addAll(DeploymentContainerArguments.from(container));
        else expected.addAll(DeploymentRuntimeArguments.from(null, runtime));
        require(words.equals(expected), "Publication arguments differ from original objects");
        var expectedIntent = RemoteActions.command(server, candidate.artifactSha256(), request).withSemantics(new ActionSemantics(PUBLISH,
            Set.of(new ActionTarget(server.id(), "deployment-release", unit.releaseRoot()),
                new ActionTarget(server.id(), "deployment-candidate", layout.candidateRoot(candidate.candidateId())))));
        require(expectedIntent.equals(intent), "Publication intent differs from original objects");
        return selected;
    }

    private void requireBinding() throws ApiException {
        try {
            require(!stopped, "Publication evidence stopped");
            var saved = journal.find(task).orElseThrow();
            var events = journal.events(task);
            require(snapshot.identity().equals(saved.identity()) && snapshot.executionSemantics() == saved.executionSemantics()
                && saved.identity().server().equals(server.id()) && saved.identity().configurationDigest().equals(configuration)
                && saved.identity().targetDigest().equals(DeploymentAgentInputs.targetDigest(server, session))
                && profile.equals(DeploymentAgentInputs.targetProfile(original.servers().get(server, task)))
                && profile.equals(profile(events)) && source.equals(originalSource(events))
                && layout.equals(DeploymentAgentLayouts.original(original, task, saved.identity(), events)), "Original publication identity changed");
            DeploymentFixedCommandApproval.requireRoute(original, task, configuration);
            for (var selected : selections.values()) {
                String component = selected.part().componentId();
                require(selected.receipts().equals(receipts(events, selected.part(), selected.candidate()))
                    && selected.workspace().equals(DeploymentAgentWorkspaceBinding.workspace(task.id(), saved, events,
                        selected.part().unit().id(), source.action(), layout))
                    && selected.workspace().candidateId().equals(selected.candidate().candidateId())
                    && selected.commit().release().sha256().equals(DeploymentAgentInputs.publicationReleaseIdentity(
                        selected.candidate().artifactSha256(), selected.candidate().sourceRevision(), selected.commit().configuration().sha256(),
                        selected.part().reviewedRuntime(), selected.part().resources())), "Original component publication binding changed: " + component);
            }
        } catch (Exception changed) {
            throw stop();
        }
    }

    private static Map<String, DeploymentAgentActionLog.Event> receipts(List<DeploymentAgentActionLog.Event> events,
                                                                       DeploymentApplicationGraph.Part part, DeploymentGraphPublisher.Candidate candidate) {
        String component = part.componentId();
        var values = new LinkedHashMap<String, DeploymentAgentActionLog.Event>();
        for (String type : List.of("CANDIDATE", "CURRENT_SOURCE", "SEALED_ARTIFACT", "SEALED_TOOLS"))
            values.put(type, latest(events, type, component));
        String seal = DeploymentAgentInputs.hash(component + ":" + candidate.sourceRevision() + ":seal");
        values.put("SEAL_EXECUTING", latest(events, "SEAL_EXECUTING", seal));
        values.put("SEAL_RESULT", latest(events, "SEAL_RESULT", seal));
        require(values.get("CANDIDATE").detail().equals(candidate.candidateId())
            && values.get("CURRENT_SOURCE").detail().equals(candidate.sourceRevision())
            && values.get("SEALED_ARTIFACT").detail().equals(candidate.artifactSha256())
            && values.get("SEALED_TOOLS").detail().equals(candidate.toolchainBindingSha256() + ":" + candidate.hasTools())
            && values.get("SEAL_EXECUTING").detail().equals(candidate.sourceRevision())
            && values.get("SEAL_RESULT").detail().equals(candidate.artifactSha256())
            && values.get("CANDIDATE").id() < values.get("CURRENT_SOURCE").id()
            && values.get("CURRENT_SOURCE").id() < values.get("SEAL_EXECUTING").id()
            && values.get("SEAL_EXECUTING").id() < values.get("SEAL_RESULT").id()
            && values.get("SEAL_RESULT").id() < values.get("SEALED_ARTIFACT").id()
            && values.get("SEALED_ARTIFACT").id() < values.get("SEALED_TOOLS").id(), "Original sealing receipts differ");
        return Map.copyOf(values);
    }

    private static DeploymentAgentActionLog.Event latest(List<DeploymentAgentActionLog.Event> events, String type, String action) {
        return events.stream().filter(value -> value.type().equals(type) && value.action().equals(action)).reduce((left, right) -> right).orElseThrow();
    }

    private static DeploymentAgentActionLog.Event originalSource(List<DeploymentAgentActionLog.Event> events) {
        var sources = events.stream().filter(value -> value.type().equals("SOURCE")).toList();
        require(sources.size() == 1 && sources.getFirst().action().matches("[0-9a-f]{64}") && sources.getFirst().detail().matches("[0-9a-f]{64}"),
            "Original source receipt missing or conflicting");
        return sources.getFirst();
    }

    private static String profile(List<DeploymentAgentActionLog.Event> events) {
        return events.stream().filter(value -> value.type().equals("TARGET_PROFILE")).map(DeploymentAgentActionLog.Event::detail).distinct()
            .reduce((left, right) -> { throw new IllegalStateException("Conflicting target profiles"); }).orElseThrow();
    }

    /** Only decodes the product's canonical shellQuote sequence; no general shell evaluation or whitespace tolerance. */
    private static List<String> literalWords(String body) {
        var words = new ArrayList<String>();
        int offset = 0;
        while (offset < body.length()) {
            require(body.charAt(offset++) == '\'', "Noncanonical publication literal");
            var value = new StringBuilder();
            boolean closed = false;
            while (offset < body.length()) {
                if (body.startsWith("'\"'\"'", offset)) {
                    value.append('\'');
                    offset += 5;
                } else if (body.charAt(offset) == '\'') {
                    offset++;
                    closed = true;
                    break;
                } else value.append(body.charAt(offset++));
            }
            require(closed, "Unclosed publication literal");
            words.add(value.toString());
            if (offset < body.length()) require(body.charAt(offset++) == ' ' && offset < body.length(), "Noncanonical publication separator");
        }
        require(!words.isEmpty() && body.equals(words.stream().map(DeploymentBuildCommandChannel::shellQuote).collect(Collectors.joining(" "))),
            "Noncanonical publication invocation");
        return List.copyOf(words);
    }

    private static int canonicalCount(String value) {
        require(value.matches("0|[1-9][0-9]*"), "Invalid publication count");
        return Integer.parseInt(value);
    }

    private static Map<String, Object> argument(int position, String role, String value) {
        return Map.of("position", position, "role", role, "value", value);
    }

    private static boolean covered(ActionIntent intent) {
        return intent.semantics().map(value -> value.operation().equals(PUBLISH)).orElse(false);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private ApiException stop() {
        stopped = true;
        return new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_AGENT_PUBLICATION_BINDING_CHANGED",
            "Original publication identity or exact pending request could not be confirmed", Map.of("task", task.id().toString()), Optional.empty()));
    }
}
