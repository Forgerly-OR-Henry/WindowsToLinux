package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import java.util.*;

import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument;
import gold.debug.windowstolinux.mod.deployment.db.execution.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentGraphPublisher;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentPublishResult;

/** Exact online failed publication accepted by the model; it grants no cleanup or retry authority. */
public record DeploymentAgentFailedPublication(DeploymentExecutionIdentity execution,
                                               DeploymentAgentActionLog.Identity identity, String layout,
                                               String source, String archive, String proposal, String candidates,
                                               long publicationEvent, long rollbackEvent, long assessmentEvent,
                                               String evidence, Problem failure, long acceptedEvent,
                                               Optional<gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint.Snapshot> commands) {
    public static final String EVENT = "AI_PUBLICATION_FAILED_ACCEPTED";

    public DeploymentAgentFailedPublication {
        Objects.requireNonNull(execution);
        Objects.requireNonNull(identity);
        Objects.requireNonNull(failure);
        Objects.requireNonNull(commands);
        for (var digest : List.of(layout, source, archive, proposal, candidates, evidence))
            if (!digest.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid failed publication digest");
        if (publicationEvent <= 0 || rollbackEvent <= publicationEvent || assessmentEvent <= rollbackEvent || acceptedEvent < 0
            || failure.code() == ProblemCode.UNKNOWN || failure.code() == ProblemCode.CANCELLED)
            throw new IllegalArgumentException("Unconfirmed failed publication");
    }

    static DeploymentAgentFailedPublication accept(ModContext context, RemoteSession session, ServerId server,
                                                   TaskContext task, String revision, DeploymentPublishResult proposal,
                                                   Map<String, DeploymentGraphPublisher.Candidate> candidates,
                                                   DeploymentAgentActionLog journal, long publication, long rollback,
                                                   Problem failure,
                                                   Optional<gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint.Snapshot> commands) throws Exception {
        var events = journal.events(task);
        var saved = journal.find(task).orElseThrow();
        var executions = new DeploymentExecutionStore(context.db());
        var record = executions.findForTask(task).orElseThrow();
        requireCurrent(context, session, server, task, revision, proposal, candidates, journal, saved, record);
        if (!sameCommands(commands, commands(context, session, server, task, revision)))
            throw new IllegalStateException("Commands changed after native publication began");
        requireIndexedResources(context, task, server, events);
        if (!record.phase().equals("EXECUTING") || record.businessCommitted()
            || record.outcome().filter(value -> value != TaskResult.UNKNOWN).isPresent())
            throw new IllegalStateException("Original publication has already ended");
        var start = event(events, publication, "GRAPH_PROPOSAL", proposal.graph().applicationId());
        var restored = event(events, rollback, "GRAPH_ROLLBACK_RESULT", proposal.graph().applicationId());
        if (start.id() >= restored.id() || !restored.detail().equals("RESTORED")
            || events.stream().filter(e -> e.type().equals("GRAPH_PROPOSAL")).count() != 1
            || events.stream().filter(e -> e.type().equals("GRAPH_ROLLBACK_RESULT")).count() != 1
            || events.stream().anyMatch(e -> e.type().equals(EVENT)))
            throw new IllegalStateException("Original native rollback is not unique");
        if (!DeploymentAgentActionLog.effectsSettled(events.stream().filter(e -> !e.type().startsWith("DELIVERY_")).toList()))
            throw new IllegalStateException("Another original mutation remains unsettled");
        var assessment = events.getLast();
        if (!assessment.type().equals("PUBLICATION_ASSESSMENT") || !Set.of("finish_failure", "continue_deployment").contains(assessment.action())
            || assessment.id() <= rollback || !assessment.detail().matches("[0-9a-f]{64}"))
            throw new IllegalStateException("Current model failure assessment is absent");
        var originalSource = events.stream().filter(e -> e.type().equals("SOURCE")).toList();
        if (originalSource.size() != 1) throw new IllegalStateException("Original source identity is not unique");
        var value = new DeploymentAgentFailedPublication(record.identity(), saved.identity(),
            proposal.graph().components().getFirst().unit().layout().digest(), originalSource.getFirst().detail(),
            originalSource.getFirst().action(), DeploymentAgentInputs.hash(proposal.toString()), candidateDigest(candidates),
            publication, rollback, assessment.id(), evidenceDigest(events), failure, 0, commands);
        journal.event(task, EVENT, proposal.graph().applicationId(), value.detail());
        var retained = journal.events(task).stream().filter(event -> event.id() > assessment.id()
            && event.type().equals(EVENT) && event.action().equals(proposal.graph().applicationId())
            && event.detail().equals(value.detail())).toList();
        if (retained.size() != 1)
            throw new IllegalStateException("Original failure acceptance was not retained");
        var accepted = retained.getFirst();
        return new DeploymentAgentFailedPublication(value.execution(), value.identity(), value.layout(), value.source(),
            value.archive(), value.proposal(), value.candidates(), value.publicationEvent(), value.rollbackEvent(),
            value.assessmentEvent(), value.evidence(), value.failure(), accepted.id(), commands);
    }

    void requireSettlement(ModContext context, RemoteSession session, ServerId server, TaskContext task,
                           String revision, DeploymentPublishResult proposal,
                           Map<String, DeploymentGraphPublisher.Candidate> candidates,
                           DeploymentAgentActionLog journal) throws Exception {
        var events = journal.events(task);
        var saved = journal.find(task).orElseThrow();
        var executions = new DeploymentExecutionStore(context.db());
        var record = executions.findForTask(task).orElseThrow();
        requireCurrent(context, session, server, task, revision, proposal, candidates, journal, saved, record);
        if (!sameCommands(commands, commands(context, session, server, task, revision)))
            throw new IllegalStateException("Commands changed after native failure acceptance");
        requireIndexedResources(context, task, server, events);
        if (!record.identity().equals(execution) || !saved.identity().equals(identity)
            || !record.phase().equals("EXECUTING") || !record.outcome().equals(Optional.of(TaskResult.FAILED))
            || record.businessCommitted() || !this.proposal.equals(DeploymentAgentInputs.hash(proposal.toString()))
            || !this.candidates.equals(candidateDigest(candidates)) || acceptedEvent <= assessmentEvent)
            throw new IllegalStateException("Original failed execution changed");
        var recordedFailure = executions.problem(record).orElseThrow();
        if (recordedFailure.code() != failure.code() || !recordedFailure.eventCode().equals(failure.eventCode()))
            throw new IllegalStateException("Original formal failure differs from its acceptance");
        var accepted = event(events, acceptedEvent, EVENT, execution.applicationId());
        if (!accepted.detail().equals(detail()) || !evidence.equals(evidenceDigest(events.stream().filter(e -> e.id() < acceptedEvent).toList())))
            throw new IllegalStateException("Original failure evidence changed");
        var deliveries = events.stream().filter(e -> e.type().equals("DELIVERY_RESULT") && e.id() > acceptedEvent).toList();
        if (deliveries.size() != 1 || !deliveries.getFirst().detail().equals("FAILED")
            || !DeploymentAgentActionLog.effectsSettled(events))
            throw new IllegalStateException("Original failed delivery has not settled");
    }

    private static void requireCurrent(ModContext context, RemoteSession session, ServerId server, TaskContext task,
                                       String revision, DeploymentPublishResult proposal,
                                       Map<String, DeploymentGraphPublisher.Candidate> candidates,
                                       DeploymentAgentActionLog journal, DeploymentAgentActionLog.Snapshot saved,
                                       DeploymentExecutionRecord record) throws Exception {
        var layout = proposal.graph().components().getFirst().unit().layout();
        var retained = new DeploymentExecutionStore(context.db()).publicationTemplate(record).orElseThrow();
        gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentWorkspaceBinding.find(task.id(), saved, journal.events(task));
        if (!Set.of(4, 5).contains(saved.executionSemantics()) || !saved.identity().server().equals(server.id())
            || !saved.identity().configurationDigest().equals(revision)
            || !saved.identity().targetDigest().equals(DeploymentAgentInputs.targetDigest(server, session))
            || !record.identity().taskId().equals(task.id()) || !record.identity().server().equals(server)
            || record.identity().mode() != DeploymentExecutionIdentity.DeploymentExecutionMode.AGENT
            || !record.identity().applicationId().equals(proposal.graph().applicationId())
            || !retained.graph().equals(proposal.graph()) || !retained.units().equals(proposal.units())
            || !gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentLayouts.original(context, task, saved.identity(), journal.events(task)).equals(layout)
            || !candidates.keySet().equals(proposal.graph().components().stream().map(p -> p.componentId()).collect(java.util.stream.Collectors.toSet())))
            throw new IllegalStateException("Original failed publication binding changed");
    }

    static Optional<gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint.Snapshot> commands(
        ModContext context, RemoteSession session, ServerId server, TaskContext task, String revision) throws Exception {
        var retained = context.tasks().findCheckpoint(task, gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner.KEY);
        if (retained.isEmpty()) return Optional.empty();
        gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner.requireRecoveryIdentity(context, task, server, revision);
        var snapshot = retained.orElseThrow().snapshot();
        var facts = new com.fasterxml.jackson.databind.ObjectMapper().readTree(snapshot.payload());
        if (!facts.path("fingerprint").asText().equals(session.facts().fingerprint())
            || facts.path("commands").findValuesAsText("phase").stream().anyMatch(value -> !value.equals("ENDED")))
            throw new IllegalStateException("Failure settlement supports only unchanged known ENDED host commands");
        return Optional.of(snapshot);
    }

    private static boolean sameCommands(Optional<gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint.Snapshot> expected,
                                        Optional<gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint.Snapshot> current) {
        if (expected.isEmpty() || current.isEmpty()) return expected.equals(current);
        var original = expected.orElseThrow();
        var observed = current.orElseThrow();
        return original.revision() == observed.revision() && original.payload().equals(observed.payload())
            && (!original.released() || observed.released());
    }

    private static void requireIndexedResources(ModContext context, TaskContext task, ServerId server,
                                                List<DeploymentAgentActionLog.Event> events) throws Exception {
        for (var event : events) {
            if (event.type().equals("AGENT_DATABASE_INDEX")) {
                if (!event.detail().matches("[0-9a-f]{64}:[0-9a-f]{64}"))
                    throw new IllegalStateException("Original database index is invalid");
                var plan = gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentDatabasePlanActionLog
                    .find(context.tasks(), task, event.action()).orElseThrow(() -> new IllegalStateException("Original indexed database checkpoint is absent"));
                if (!plan.facts().serverId().equals(server.id()) || !plan.facts().manifestSha256().equals(event.detail().substring(0, 64)))
                    throw new IllegalStateException("Original indexed database binding changed");
            } else if (event.type().equals("AGENT_SECRET_INDEX")) {
                if (!event.action().matches("deployment-secret-revision\\.[0-9a-f]{32}")
                    || !event.detail().matches("[0-9a-f]{64}:deployment-(runtime|database)")
                    || context.tasks().findCheckpoint(task, event.action()).isEmpty())
                    throw new IllegalStateException("Original indexed secret checkpoint is absent or invalid");
            }
        }
    }

    public String detail() {
        return DeploymentJsonDocument.object().put("formatVersion", 1).put("task", execution.taskId().toString())
            .put("execution", execution.executionId().toString()).put("executionIdentity", DeploymentAgentInputs.hash(execution.toString()))
            .put("server", execution.server().id()).put("application", execution.applicationId())
            .put("target", identity.targetDigest()).put("configuration", identity.configurationDigest())
            .put("policy", DeploymentAgentInputs.hash(identity.toString())).put("layout", layout).put("source", source)
            .put("archive", archive).put("proposal", proposal).put("candidates", candidates)
            .put("publicationEvent", publicationEvent).put("rollbackEvent", rollbackEvent).put("assessmentEvent", assessmentEvent)
            .put("evidence", evidence).put("failureCode", failure.code().name()).put("failureEvent", failure.eventCode())
            .put("failure", DeploymentAgentInputs.hash(failure.toString())).put("commands", DeploymentAgentInputs.hash(commands.toString())).toString();
    }

    private static DeploymentAgentActionLog.Event event(List<DeploymentAgentActionLog.Event> events, long id,
                                                        String type, String action) {
        var selected = events.stream().filter(e -> e.id() == id).findFirst().orElseThrow();
        if (!selected.type().equals(type) || !selected.action().equals(action))
            throw new IllegalStateException("Original failure event binding changed");
        return selected;
    }

    private static String evidenceDigest(List<DeploymentAgentActionLog.Event> events) {
        return DeploymentAgentInputs.hash(events.toString());
    }

    private static String candidateDigest(Map<String, DeploymentGraphPublisher.Candidate> candidates) {
        var node = DeploymentJsonDocument.object().put("formatVersion", 1);
        var entries = node.putArray("candidates");
        new TreeMap<>(candidates).forEach((id, value) -> {
            var credentials = new TreeMap<String, String>();
            value.credentials().forEach((reference, secret) -> credentials.put(reference.toString(), secret.toString()));
            entries.addObject().put("component", id).put("candidate", value.candidateId())
                .put("artifact", value.artifactSha256()).put("source", value.sourceRevision()).put("tools", value.toolchainBindingSha256())
                .put("hasTools", value.hasTools()).put("credentials", DeploymentAgentInputs.hash(credentials.toString()));
        });
        return DeploymentAgentInputs.hash(node.toString());
    }
}
