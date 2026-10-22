package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;

/** Fixed protocols shared by deployment modes, with no mode-specific interpretation. */
public final class DeploymentFixedCommandProtocol implements DeploymentCommandProtocol {
    private static final Set<String> OPERATIONS = Set.of(
        "deployment.helper.install.v1",
        "deployment.helper.install.tracked.v1",
        "deployment.helper.install.files.v1",
        "deployment.helper.install.status.v1",
        "deployment.helper.probe.v1",
        "deployment.candidate.host-probe.v1",
        "deployment.candidate.container-probe.v1",
        "deployment.candidate.capacity.v1",
        "deployment.candidate.create.v1",
        "deployment.candidate.query.v1",
        "deployment.candidate.upload.v1",
        "deployment.candidate.verify.v1",
        "deployment.toolchain.prepare.v1",
        "deployment.toolchain.bind.v1",
        "deployment.build.preflight.v1",
        "deployment.build.run.v1",
        "deployment.publication.snapshot.v1",
        "deployment.publication.original-status.v1",
        "deployment.publication.snapshot-discard.v1",
        "deployment.publication.config.v1",
        "deployment.publication.secret.v1",
        "deployment.publication.publish.v1",
        "deployment.publication.health.v1",
        "deployment.publication.stop-old.v1",
        "deployment.publication.inspect.v1",
        "deployment.publication.observe.v1",
        "deployment.publication.observe-restored.v1",
        "deployment.publication.rollback.v1",
        "deployment.publication.retain.v1",
        "deployment.publication.maintenance-end.v1",
        "deployment.publication.candidate-cleanup.v1",
        "deployment.application.lifecycle.v1",
        "deployment.handoff.managed-current-status.v1",
        "deployment.native-db.inspect.v1",
        "deployment.native-db.target.v1",
        "deployment.native-db.start.v1",
        "deployment.native-db.install.v1",
        "deployment.native-db.prepare.v1",
        "deployment.native-db.initialize.v1",
        "deployment.native-db.resume.v1",
        "deployment.native-db.status.v1",
        "deployment.model.command.v1",
        "deployment.model.command.status.v1");
    private final ModContext context;

    public DeploymentFixedCommandProtocol(ModContext context) {
        this.context = Objects.requireNonNull(context);
    }
    public static boolean registeredOperation(String operation) {
        return OPERATIONS.contains(operation);
    }
    public boolean registered(ActionIntent intent) {
        return intent.semantics().filter(value -> registeredOperation(value.operation())).isPresent();
    }
    public Map<String, Object> inspect(ActionIntent intent, CommandRequest request, DeploymentRemoteLayout layout,
                                       TaskContext task) throws IOException, ApiException {
        if (!registered(intent)) throw new IllegalArgumentException("Unsupported common command operation");
        requireTaskTargets(intent, task);
        DeploymentCommandStaticGate.requireFixedAllowed(request.script());
        var facts = DeploymentCommandEvidence.facts(intent, request, layout, context, task);
        if (facts.get("modelCommand") instanceof Map<?, ?> command && !task.id().toString().equals(command.get("task")))
            throw new IllegalArgumentException("Model command task differs from original context");
        if (facts.get("originalPublicationStatus") instanceof Map<?, ?> status && !task.id().toString().equals(status.get("task")))
            throw new IllegalArgumentException("Publication status task differs from original context");
        return facts;
    }
    /** A declared task target can only refer to the authentic original task. */
    public static void requireTaskTargets(ActionIntent intent, TaskContext task) {
        for (var target : intent.semantics().orElseThrow().targets())
            if (target.kind().equals("task") && !target.id().equals(task.id().toString()))
                throw new IllegalArgumentException("Command resource task differs from original context");
    }
}
