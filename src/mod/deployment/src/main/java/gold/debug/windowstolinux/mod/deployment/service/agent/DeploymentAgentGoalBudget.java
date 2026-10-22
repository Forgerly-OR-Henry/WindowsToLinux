package gold.debug.windowstolinux.mod.deployment.service.agent;

import java.util.*;

import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildBudget;

/** Frozen goal allowances; fresh task-local protocols remain separate and keep their original evidence. */
public final class DeploymentAgentGoalBudget {
    public record Snapshot(int maximumResponses, int mainResponses, int nestedResponses,
                           int maximumTools, int sharedTools, int mainTools,
                           long hostCommands, long declaredHostSeconds, DeploymentBuildBudget.Snapshot build) {
        public Snapshot {
            Objects.requireNonNull(build);
            if (maximumResponses < 1 || mainResponses < 0 || nestedResponses < 0
                || (long) mainResponses + nestedResponses > maximumResponses || maximumTools < 1
                || sharedTools < 0 || sharedTools > maximumTools || mainTools < 0 || mainTools > sharedTools
                || hostCommands < 0 || declaredHostSeconds < 0)
                throw new IllegalArgumentException("Invalid original goal allowance");
        }

        public int remainingResponses() {
            return maximumResponses - mainResponses - nestedResponses;
        }

        public int remainingTools() {
            return maximumTools - sharedTools;
        }
    }

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private final int maximumResponses;

    private final int maximumTools;

    private int mainResponses;

    private int nestedResponses;

    private int sharedTools;

    private int mainTools;

    private long hostCommands;

    private long declaredHostSeconds;

    private final DeploymentBuildBudget build;

    public DeploymentAgentGoalBudget(DeploymentBuildBudget build) {
        this(new Snapshot(DeploymentConfigLoader.current().ai().execution().agentModelResponses(), 0, 0,
            DeploymentConfigLoader.current().ai().execution().sharedToolAttempts(), 0, 0, 0, 0, build.snapshot()));
    }

    public DeploymentAgentGoalBudget(Snapshot saved) {
        maximumResponses = saved.maximumResponses();
        maximumTools = saved.maximumTools();
        mainResponses = saved.mainResponses();
        nestedResponses = saved.nestedResponses();
        sharedTools = saved.sharedTools();
        mainTools = saved.mainTools();
        hostCommands = saved.hostCommands();
        declaredHostSeconds = saved.declaredHostSeconds();
        build = new DeploymentBuildBudget(saved.build());
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(maximumResponses, mainResponses, nestedResponses, maximumTools, sharedTools, mainTools,
            hostCommands, declaredHostSeconds, build.snapshot());
    }

    public DeploymentBuildBudget build() {
        return build;
    }

    public static Optional<DeploymentAgentGoalBudget> current() {
        return Optional.ofNullable(CURRENT.get()).map(scope -> scope.budget);
    }

    public Scope open(TaskContext task) {
        return new Scope(this, task);
    }

    /** Restores the Agent coordinator's allowance and binds future charges to this actual goal and task. */
    public static gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolBudget openToolBudget(TaskContext task) {
        var scope = CURRENT.get();
        if (scope == null)
            return gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolBudget.open(
                DeploymentConfigLoader.current().ai().execution().sharedToolAttempts());
        scope.require(task);
        var original = scope.budget.snapshot();
        return gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolBudget.open(
            original.maximumTools(), original.sharedTools(), used -> {
                scope.require(task);
                sharedAttempts(used);
            });
    }

    public static void beforeModel(TaskContext task, boolean main) {
        var scope = CURRENT.get();
        if (scope == null) return;
        scope.require(task);
        synchronized (scope.budget) {
            if (scope.budget.snapshot().remainingResponses() < 1)
                throw new IllegalStateException("Original goal model allowance exhausted");
            if (main) scope.budget.mainResponses++;
            else scope.budget.nestedResponses++;
        }
    }

    public static void mainAttempts(TaskContext task, int count) {
        var scope = CURRENT.get();
        if (scope == null) return;
        scope.require(task);
        if (count < 0) throw new IllegalArgumentException("Invalid main attempt count");
        synchronized (scope.budget) {
            scope.budget.mainTools = Math.min(scope.budget.maximumTools, Math.addExact(scope.budget.mainTools, count));
        }
    }

    public static void sharedAttempts(int totalUsed) {
        var scope = CURRENT.get();
        if (scope == null) return;
        scope.requireThread();
        synchronized (scope.budget) {
            if (totalUsed < scope.budget.sharedTools || totalUsed > scope.budget.maximumTools)
                throw new IllegalStateException("Original goal tool allowance cannot be refunded");
            scope.budget.sharedTools = totalUsed;
            if (scope.budget.mainTools > totalUsed)
                throw new IllegalStateException("Main attempts exceed all attempts");
        }
    }

    public static void hostCommand(TaskContext task, int seconds) {
        var scope = CURRENT.get();
        if (scope == null) return;
        scope.require(task);
        if (seconds < 1 || seconds > scope.budget.build.remainingSeconds())
            throw new IllegalArgumentException("Original goal execution allowance is insufficient for this exact command");
        synchronized (scope.budget) {
            scope.budget.hostCommands = Math.addExact(scope.budget.hostCommands, 1);
            scope.budget.declaredHostSeconds = Math.addExact(scope.budget.declaredHostSeconds, seconds);
        }
    }

    /** Borrowed only by the per-task host-command protocol; existing timed channels stay unwrapped. */
    public static gold.debug.windowstolinux.appapi.linux.session.RemoteSession timedHostSession(
        gold.debug.windowstolinux.appapi.linux.session.RemoteSession delegate, TaskContext task, int seconds) {
        var scope = CURRENT.get();
        if (scope == null) return delegate;
        hostCommand(task, seconds);
        var budget = scope.budget.build;
        return new gold.debug.windowstolinux.appapi.linux.session.RemoteSession() {
            public gold.debug.windowstolinux.appapi.linux.session.RemoteFacts facts() {
                return delegate.facts();
            }

            public gold.debug.windowstolinux.appapi.task.result.OperationResult<gold.debug.windowstolinux.appapi.linux.command.CommandResult> execute(
                gold.debug.windowstolinux.appapi.linux.command.CommandRequest request,
                gold.debug.windowstolinux.appapi.task.action.ApprovalGrant grant) throws gold.debug.windowstolinux.appapi.task.result.ApiException {
                long started = System.nanoTime();
                try {
                    return delegate.execute(request, grant);
                } finally {
                    budget.charge(System.nanoTime() - started);
                }
            }

            public gold.debug.windowstolinux.appapi.task.result.OperationResult<gold.debug.windowstolinux.appapi.linux.transfer.TransferResult> upload(
                gold.debug.windowstolinux.appapi.linux.transfer.TransferRequest request, gold.debug.windowstolinux.appapi.task.action.ApprovalGrant grant)
                throws gold.debug.windowstolinux.appapi.task.result.ApiException {
                return delegate.upload(request, grant);
            }

            public gold.debug.windowstolinux.appapi.task.result.OperationResult<gold.debug.windowstolinux.appapi.linux.transfer.TransferResult> upload(
                gold.debug.windowstolinux.appapi.linux.transfer.TransferRequest request, gold.debug.windowstolinux.appapi.task.action.ApprovalGrant grant,
                gold.debug.windowstolinux.appapi.linux.transfer.TransferConflictChoice choice) throws gold.debug.windowstolinux.appapi.task.result.ApiException {
                return delegate.upload(request, grant, choice);
            }

            public gold.debug.windowstolinux.appapi.task.result.OperationResult<gold.debug.windowstolinux.appapi.linux.transfer.TransferResult> download(
                gold.debug.windowstolinux.appapi.linux.transfer.TransferRequest request, gold.debug.windowstolinux.appapi.task.action.ApprovalGrant grant)
                throws gold.debug.windowstolinux.appapi.task.result.ApiException {
                return delegate.download(request, grant);
            }

            public gold.debug.windowstolinux.appapi.task.result.OperationResult<gold.debug.windowstolinux.appapi.linux.transfer.TransferResult> download(
                gold.debug.windowstolinux.appapi.linux.transfer.TransferRequest request, gold.debug.windowstolinux.appapi.task.action.ApprovalGrant grant,
                gold.debug.windowstolinux.appapi.linux.transfer.TransferConflictChoice choice) throws gold.debug.windowstolinux.appapi.task.result.ApiException {
                return delegate.download(request, grant, choice);
            }

            public gold.debug.windowstolinux.appapi.task.result.OperationResult<gold.debug.windowstolinux.appapi.linux.transfer.TransferResult> resumeTransfer(
                UUID id, gold.debug.windowstolinux.appapi.task.action.ApprovalGrant grant) throws gold.debug.windowstolinux.appapi.task.result.ApiException {
                return delegate.resumeTransfer(id, grant);
            }

            public gold.debug.windowstolinux.appapi.task.result.OperationResult<Void> discardTransfer(UUID id,
                                                                                                      gold.debug.windowstolinux.appapi.task.action.ApprovalGrant grant) throws gold.debug.windowstolinux.appapi.task.result.ApiException {
                return delegate.discardTransfer(id, grant);
            }

            public void close() throws gold.debug.windowstolinux.appapi.task.result.ApiException {
                delegate.close();
            }
        };
    }

    public static final class Scope implements AutoCloseable {
        private final DeploymentAgentGoalBudget budget;

        private final TaskContext task;

        private final Thread owner = Thread.currentThread();

        private Scope(DeploymentAgentGoalBudget budget, TaskContext task) {
            if (CURRENT.get() != null) throw new IllegalStateException("Original goal scope already exists");
            this.budget = Objects.requireNonNull(budget);
            this.task = Objects.requireNonNull(task);
            CURRENT.set(this);
        }

        private void requireThread() {
            if (Thread.currentThread() != owner || CURRENT.get() != this)
                throw new IllegalStateException("Original goal scope owner changed");
        }

        private void require(TaskContext supplied) {
            requireThread();
            if (supplied != task || !supplied.id().equals(task.id()))
                throw new IllegalStateException("Original goal belongs to another actual task");
        }

        @Override
        public void close() {
            requireThread();
            CURRENT.remove();
        }
    }
}
