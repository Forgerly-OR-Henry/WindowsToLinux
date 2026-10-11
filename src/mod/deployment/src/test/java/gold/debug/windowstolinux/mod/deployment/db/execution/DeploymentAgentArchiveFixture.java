package gold.debug.windowstolinux.mod.deployment.db.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.ref.ResourceRefResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentOriginalPublishCommit;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentPublishResult;

/** Constructs committed local evidence without any model or Linux session. / 构造本地提交证据，不建立模型或Linux会话。 */
public abstract class DeploymentAgentArchiveFixture extends DeploymentExecutionFixture {
    protected UUID preparedSuccess(PlatformRuntime runtime, TaskContext task) throws Exception {
        var context = runtime.modContexts().get(OWNER).context();
        var journal = new DeploymentAgentActionLog(context.db());
        journal.create(task, agentIdentity());
        var proposal = new DeploymentPublishResult(graph("application"), publications(graph("application"), 1, List.of()));
        var id = UUID.randomUUID();
        var identity = new DeploymentExecutionIdentity(id, task.id(), Optional.empty(), "application", new ServerId("server"),
            DeploymentExecutionIdentity.DeploymentExecutionMode.AGENT, "a".repeat(64));
        var store = new DeploymentExecutionStore(context.db());
        var intents = DeploymentOriginalPublishCommit.references(new ServerId("server"), proposal, Map.of());
        store.begin(task, identity, "deployment-agent", intents, Optional.empty(), OBSERVED);
        var refs = new ArrayList<UUID>();
        for (var intent : intents) {
            var ref = context.servers().reserve(new ServerId("server"), intent.businessKey(), task);
            refs.add(ref.id());
            store.recordReference(task, id, intent, ref.id(), OBSERVED.plusSeconds(1));
        }
        store.startEffects(task, id, OBSERVED.plusSeconds(2));
        store.recordPublicationTemplate(task, id, new gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentPublishProposal(proposal.graph(), proposal.units()), OBSERVED.plusSeconds(3));
        store.commitApplication(task, id, proposal.graph(), proposal.units(), OBSERVED.plusSeconds(4));
        for (UUID ref : refs) context.servers().resolve(ref, ResourceRefResult.REFERENCED, task);
        store.finishCleanup(task, id, OBSERVED.plusSeconds(5));
        journal.event(task, "AI_PUBLICATION_FINISHED", "application", "MODEL_ACCEPTED_VERIFIED_RESULT");
        journal.event(task, "AI_PUBLICATION_CLEANED", "application", "ABSENT");
        journal.state(task, "SUCCEEDED");
        return id;
    }

    protected static DeploymentAgentActionLog.Identity agentIdentity() {
        return new DeploymentAgentActionLog.Identity("server", "a".repeat(64), "b".repeat(64), "MANUAL_REVIEW", true);
    }
}
