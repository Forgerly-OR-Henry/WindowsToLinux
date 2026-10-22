package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildBudget;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildCommandChannel;
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard;
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentCommandReviewRouter;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview;
import gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentAlternativeRequiredException;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;

/** Sends only fixed Agent observations through fresh independent reviews and original dispatch guards. / 仅经独立新审核及原发送守卫发送固定 Agent 观测。 */
public final class DeploymentAgentHostChannel {
    private final DeploymentBuildCommandChannel commands;

    private final TaskContext task;

    private final String server;

    private final DeploymentSendGuard beforeSend;

    public DeploymentAgentHostChannel(RemoteSession session, InteractionService interaction, ServerId server, TaskContext task,
                                      String revision, DeploymentBuildBudget budget, DeploymentSendGuard beforeSend) {
        this(session, interaction, server, task, revision, budget, beforeSend, DeploymentRemoteLayout.original());
    }

    public DeploymentAgentHostChannel(RemoteSession session, InteractionService interaction, ServerId server, TaskContext task,
                                      String revision, DeploymentBuildBudget budget, DeploymentSendGuard beforeSend, DeploymentRemoteLayout layout) {
        this.task = Objects.requireNonNull(task);
        this.server = Objects.requireNonNull(server).id();
        this.beforeSend = Objects.requireNonNull(beforeSend);
        commands = new DeploymentBuildCommandChannel(session, new HostReview(Objects.requireNonNull(interaction), task), server, task, revision, budget, () -> {
            if (task.cancelToken().requested()) throw cancelled();
            beforeSend.verify();
        }, layout);
    }

    private record HostReview(InteractionService original, TaskContext task) implements DeploymentCommandReview {
        public OperationResult<ApprovalGrant> approveCommand(ActionIntent intent, CommandRequest request, TaskContext supplied) throws ApiException {
            if (supplied != task) throw new IllegalArgumentException("Host review differs from its original task");
            try {
                return DeploymentCommandReviewRouter.approve(original, intent, request, supplied);
            } catch (ApiException denied) {
                if (original instanceof DeploymentCommandReview && denied.problem().code() == ProblemCode.ACCESS_DENIED
                    && denied.problem().eventCode().equals("DEPLOYMENT_COMMAND_REVIEW_DENIED")
                    && task.id().toString().equals(denied.problem().context().get("task")))
                    throw DeploymentAgentAlternativeRequiredException.fromReview(task, denied);
                throw denied;
            }
        }

        public OperationResult<ApprovalGrant> approve(ActionIntent intent, TaskContext supplied) throws ApiException {
            return original.approve(intent, supplied);
        }

        public OperationResult<ApprovalGrant> approveRequired(ActionIntent intent, TaskContext supplied) throws ApiException {
            return original.approveRequired(intent, supplied);
        }

        public OperationResult<String> requestText(String title, String prompt, boolean secret, TaskContext supplied) throws ApiException {
            return original.requestText(title, prompt, secret, supplied);
        }
    }

    /** Observes the baseline without invoking any container or ecosystem tools. / 观测基线，不调用容器或生态工具。 */
    public OperationResult<DeploymentAgentHostFacts> baseline() throws ApiException {
        var result = send("deployment.candidate.host-probe.v1", DeploymentAgentHostProbe.baseline());
        if (result.outcome() != TaskResult.SUCCEEDED) return withoutValue(result);
        try {
            return success(new DeploymentAgentHostFacts(DeploymentAgentHostParser.baseline(result.value().orElseThrow().stdout()), Optional.empty()));
        } catch (IllegalArgumentException malformed) {
            return invalid("DEPLOYMENT_AGENT_BASELINE_INVALID");
        }
    }

    /** Adds one separately approved selected-engine observation when the actual runtime is a container. / 实际运行时为容器时，增加一份单独审批的所选引擎观测。 */
    public OperationResult<DeploymentAgentHostFacts> forRuntime(DeploymentRuntimeDefinition runtime) throws ApiException {
        Objects.requireNonNull(runtime);
        var baseline = baseline();
        if (baseline.outcome() != TaskResult.SUCCEEDED || !(runtime instanceof DeploymentRuntimeDefinition.Container container))
            return baseline;
        var result = send("deployment.candidate.container-probe.v1", DeploymentAgentHostProbe.container(container.engine()));
        if (result.outcome() != TaskResult.SUCCEEDED) return withoutValue(result);
        try {
            return success(new DeploymentAgentHostFacts(baseline.value().orElseThrow().baseline(),
                Optional.of(DeploymentAgentHostParser.container(result.value().orElseThrow().stdout(), container.engine()))));
        } catch (IllegalArgumentException malformed) {
            return invalid("DEPLOYMENT_AGENT_CONTAINER_OBSERVATION_INVALID");
        }
    }

    private OperationResult<CommandResult> send(String operation, String script) throws ApiException {
        if (task.cancelToken().requested())
            return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(), Optional.of(cancelled().problem()));
        beforeSend.verify();
        final OperationResult<CommandResult> result;
        try {
            result = commands.send(operation, "deployment-host", server, script, new byte[0], Duration.ofMinutes(2), DeploymentAgentHostParser.OUTPUT_LIMIT);
        } catch (ApiException failure) {
            if (failure.problem().code() != ProblemCode.CANCELLED) throw failure;
            return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(), Optional.of(failure.problem()));
        }
        if (result.outcome() != TaskResult.SUCCEEDED) return result;
        if (result.value().isEmpty() || !result.value().orElseThrow().exitCode().equals(Optional.of(0)) || result.value().orElseThrow().truncated())
            return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.IO_FAILURE,
                "DEPLOYMENT_AGENT_HOST_OBSERVATION_UNCONFIRMED", "Agent host observation has no complete zero-exit receipt", Map.of(), Optional.empty())));
        return result;
    }

    private ApiException cancelled() {
        return new ApiException(new Problem(ProblemCode.CANCELLED, "DEPLOYMENT_AGENT_HOST_OBSERVATION_CANCELLED",
            "Agent host observation was cancelled before dispatch", Map.of("task", task.id().toString()), Optional.of(task.id())));
    }

    private static <T> OperationResult<T> withoutValue(OperationResult<?> result) {
        return new OperationResult<>(result.outcome(), Optional.empty(), result.problem());
    }

    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    private static <T> OperationResult<T> invalid(String event) {
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.INVALID_INPUT,
            event, "Agent host observation receipt is incomplete or malformed", Map.of(), Optional.empty())));
    }
}
