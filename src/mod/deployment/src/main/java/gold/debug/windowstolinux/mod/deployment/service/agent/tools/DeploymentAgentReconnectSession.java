package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import java.util.*;

import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.linux.session.*;
import gold.debug.windowstolinux.appapi.linux.transfer.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.server.model.ServerInfo;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Keeps every Agent channel on one original target; replacement never sends a command. */
final class DeploymentAgentReconnectSession implements RemoteSession {
    private final ModContext context;

    private final TaskContext task;

    private final RemoteTarget target;

    private final ServerInfo originalProfile;

    private final String configuration;

    private RemoteSession active;

    private OperationResult<CommandResult> lastCommand;

    private boolean eligible, uncertain, ownsActive;

    DeploymentAgentReconnectSession(ModContext context, TaskContext task, ServerId server, RemoteSession original, String configuration) throws ApiException {
        this.context = context;
        this.task = task;
        active = original;
        this.configuration = configuration;
        var profile = context.servers().get(server, task);
        originalProfile = profile;
        target = new RemoteTarget(server, task.id(), profile.host(), profile.port(), profile.username(),
            Optional.of(original.facts().fingerprint()));
    }

    boolean armCapacityFailure(Problem problem) throws ApiException {
        if (uncertain || lastCommand == null || lastCommand.outcome() != TaskResult.FAILED
            || lastCommand.problem().orElse(null) != problem
            || !problem.eventCode().equals("SSH_COMMAND_AUTHOR_PAYLOAD_NOT_SENT")
            || !"NOT_SENT".equals(problem.context().get("authorPayloadState"))
            || !"false".equals(problem.context().get("inputSenderCreated"))
            || !"NOT_STARTED".equals(problem.context().get("inputState"))) return false;
        try {
            requireOriginal();
            active.close();
            requireOriginal();
            eligible = true;
        } catch (ApiException | RuntimeException failure) {
            uncertain = true;
            throw failure;
        }
        return true;
    }

    boolean available() {
        if (!eligible || uncertain) return false;
        try {
            requireOriginal();
            return true;
        } catch (ApiException unavailable) {
            return false;
        }
    }

    Map<String, Object> reconnect() throws ApiException {
        if (!available())
            throw new IllegalArgumentException("RECONNECT_UNAVAILABLE: no current author-payload absence proof");
        requireOriginal();
        eligible = false;
        try {
            requireOriginal();
            active = context.linux().connect(target, task);
            ownsActive = true;
            if (!target.fingerprint().orElseThrow().equals(active.facts().fingerprint()))
                throw new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_AGENT_RECONNECT_TARGET_CHANGED",
                    "Replacement wire key differs from the original target", Map.of(), Optional.of(task.id())));
            lastCommand = null;
            return Map.of("task", task.id().toString(), "server", target.server().id(),
                "fingerprint", active.facts().fingerprint(), "connection", "REPLACED", "commandsReplayed", 0);
        } catch (ApiException | RuntimeException failure) {
            uncertain = true;
            throw failure;
        }
    }

    private void requireOriginal() throws ApiException {
        var profile = context.servers().get(target.server(), task);
        if (task.cancelToken().requested() || !profile.ref().equals(originalProfile.ref()) || !profile.name().equals(originalProfile.name())
            || !profile.host().equals(target.host()) || profile.port() != target.port() || !profile.username().equals(target.username())
            || !profile.trustedFingerprint().equals(originalProfile.trustedFingerprint())
            || profile.trustedFingerprint().filter(key -> !key.equals(target.fingerprint().orElseThrow())).isPresent()
            || !configuration.equals(context.ai().configurationRevision(AiPurpose.LANGUAGE, task))
            || !context.linux().pendingTransfers(task).isEmpty())
            throw new ApiException(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_AGENT_RECONNECT_UNSETTLED",
                "Original target or task operation boundary cannot be confirmed", Map.of(), Optional.of(task.id())));
    }

    private RemoteSession ready() throws ApiException {
        if (eligible)
            throw new IllegalArgumentException("RECONNECT_REQUIRED: original connection is closed; this command was not dispatched");
        if (uncertain)
            throw new ApiException(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_AGENT_CONNECTION_UNSETTLED",
                "The original connection requires explicit replacement or reconciliation", Map.of(), Optional.of(task.id())));
        return active;
    }

    void requireConnection() throws ApiException {
        ready();
    }

    private <T> OperationResult<T> observe(OperationResult<T> result) {
        if (result.outcome() == TaskResult.UNKNOWN) uncertain = true;
        return result;
    }

    @Override
    public RemoteFacts facts() {
        return active.facts();
    }

    @Override
    public OperationResult<CommandResult> execute(CommandRequest request, ApprovalGrant grant) throws ApiException {
        lastCommand = null;
        lastCommand = observe(ready().execute(request, grant));
        return lastCommand;
    }

    @Override
    public OperationResult<TransferResult> upload(TransferRequest request, ApprovalGrant grant) throws ApiException {
        lastCommand = null;
        return observe(ready().upload(request, grant));
    }

    @Override
    public OperationResult<TransferResult> upload(TransferRequest request, ApprovalGrant grant, TransferConflictChoice choice) throws ApiException {
        lastCommand = null;
        return observe(ready().upload(request, grant, choice));
    }

    @Override
    public OperationResult<TransferResult> download(TransferRequest request, ApprovalGrant grant) throws ApiException {
        lastCommand = null;
        return observe(ready().download(request, grant));
    }

    @Override
    public OperationResult<TransferResult> download(TransferRequest request, ApprovalGrant grant, TransferConflictChoice choice) throws ApiException {
        lastCommand = null;
        return observe(ready().download(request, grant, choice));
    }

    @Override
    public OperationResult<TransferResult> resumeTransfer(UUID id, ApprovalGrant grant) throws ApiException {
        lastCommand = null;
        return observe(ready().resumeTransfer(id, grant));
    }

    @Override
    public OperationResult<Void> discardTransfer(UUID id, ApprovalGrant grant) throws ApiException {
        lastCommand = null;
        return observe(ready().discardTransfer(id, grant));
    }

    /** The workflow still owns the initial borrowed session; every replacement is retained until close succeeds. */
    @Override
    public void close() throws ApiException {
        if (ownsActive) {
            active.close();
            ownsActive = false;
        }
    }
}
