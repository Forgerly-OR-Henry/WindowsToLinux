package gold.debug.windowstolinux.mod.deployment.service.agent;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.*;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.file.*;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildBudget;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentDeliveryPort;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentFailedPublication;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentPublishResult;

/** One serial online goal; each real platform handle keeps its own immutable conclusion. */
public final class DeploymentAgentGoal {
    public record Observation(UUID root, UUID current, List<UUID> tasks, String state, String reason,
                              Optional<UUID> finalTask, DeploymentAgentGoalBudget.Snapshot allowance) {
        public Observation {
            tasks = List.copyOf(tasks);
        }
    }

    public record Result(UUID root, UUID finalTask, List<UUID> predecessors,
                         OperationResult<DeploymentAgentDeliveryPort.Published> result) {
        public Result {
            predecessors = List.copyOf(predecessors);
        }
    }

    @FunctionalInterface
    interface Submit {
        TaskHandle<DeploymentAgentDeliveryPort.Published> child(Pending pending) throws Exception;
    }

    private static final ThreadLocal<Worker> CURRENT = new ThreadLocal<>();

    private final ModContext context;

    private final DeploymentAgentActionLog journal;

    private final ServerId server;

    private final DeploymentAgentInputs.Request request;

    private final Submit submit;

    private final Map<UUID, Pending> pending = new LinkedHashMap<>();

    private final Map<UUID, TaskHandle<DeploymentAgentDeliveryPort.Published>> handles = new LinkedHashMap<>();

    private final List<DeploymentAgentContinuationSource> retainedPrivateCopies = new ArrayList<>();

    private final List<Consumer<TaskHandle<DeploymentAgentDeliveryPort.Published>>> observers = new ArrayList<>();

    private final CompletableFuture<Result> completion = new CompletableFuture<>();

    private UUID root;

    private UUID current;

    private DeploymentAgentGoalBudget budget;

    private volatile boolean cancelled;

    private String blocked = "";

    private gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalPolicies.Confirmed approvalPolicy;

    DeploymentAgentGoal(ModContext context, ServerId server, DeploymentAgentInputs.Request request, Submit submit) {
        this.context = context;
        this.journal = new DeploymentAgentActionLog(context.db());
        this.server = server;
        this.request = request;
        this.approvalPolicy = new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalPolicies.Confirmed(request.approvalMode(), 0);
        this.submit = submit;
        this.budget = new DeploymentAgentGoalBudget(new DeploymentBuildBudget(
            gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().build().timeoutSeconds()));
    }

    public static Optional<Worker> currentWorker() {
        return Optional.ofNullable(CURRENT.get());
    }

    public synchronized Optional<TaskHandle<DeploymentAgentDeliveryPort.Published>> currentHandle() {
        return Optional.ofNullable(handles.get(current));
    }

    public CompletionStage<Result> completion() {
        return completion;
    }

    public synchronized gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalPolicies.Confirmed approvalPolicy() {
        return approvalPolicy;
    }

    public synchronized Observation observation() {
        String state = completion.isDone() ? "FINISHED" : !blocked.isEmpty() ? "BLOCKED" : "RUNNING";
        var handle = handles.get(current);
        if (!completion.isDone() && handle != null && handle.snapshot().state() == gold.debug.windowstolinux.appapi.task.model.TaskState.UNKNOWN)
            state = "BLOCKED";
        return new Observation(root, current, List.copyOf(handles.keySet()), state, blocked,
            completion.isDone() ? Optional.of(completion.join().finalTask()) : Optional.empty(), budget.snapshot());
    }

    public AutoCloseable watch(Consumer<TaskHandle<DeploymentAgentDeliveryPort.Published>> observer) {
        TaskHandle<DeploymentAgentDeliveryPort.Published> selected;
        synchronized (this) {
            observers.add(Objects.requireNonNull(observer));
            selected = handles.get(current);
        }
        if (selected != null) observer.accept(selected);
        return () -> {
            synchronized (this) {
                observers.remove(observer);
            }
        };
    }

    public void cancel() throws ApiException {
        TaskHandle<DeploymentAgentDeliveryPort.Published> selected;
        synchronized (this) {
            cancelled = true;
            for (var value : pending.values()) value.gate.complete(false);
            selected = handles.get(current);
            if (selected == null || selected.snapshot().state() == gold.debug.windowstolinux.appapi.task.model.TaskState.ENDED) {
                blocked = "GOAL_CANCELLED_BEFORE_SUCCESSOR_EXECUTION";
                for (var value : pending.values()) closePrivate(value.source);
            }
        }
        if (selected != null && selected.snapshot().state() != gold.debug.windowstolinux.appapi.task.model.TaskState.ENDED)
            selected.requestCancel();
    }

    synchronized Worker rootWorker(TaskContext task) {
        if (root != null && !root.equals(task.id())) throw new IllegalStateException("Original goal root changed");
        root = task.id();
        current = task.id();
        return new Worker(this, task, Optional.empty(), budget);
    }

    Worker claimWorker(TaskContext task, Pending value) throws Exception {
        // This must stay before capsule access, file capabilities, source grants, connection and model admission.
        var claim = journal.claimSuccessor(task, value.ticket.intent(), context.tasks());
        if (!claim.first()) throw new IllegalStateException("A repeated child callback cannot execute business work");
        if (!value.gate.get()) {
            journal.blockSuccessor(task, value.ticket.intent().predecessor());
            throw new IllegalStateException("Successor submission was not confirmed by its actual handle");
        }
        synchronized (this) {
            if (cancelled) {
                journal.blockSuccessor(task, value.ticket.intent().predecessor());
                throw new CancellationException("Original goal cancelled");
            }
            budget = new DeploymentAgentGoalBudget(claim.ticket().settledAllowance());
            return new Worker(this, task, Optional.of(value.ticket.intent()), budget);
        }
    }

    void attach(TaskHandle<DeploymentAgentDeliveryPort.Published> handle) {
        List<Consumer<TaskHandle<DeploymentAgentDeliveryPort.Published>>> notify;
        synchronized (this) {
            if (root == null) root = handle.id();
            current = handle.id();
            if (handles.putIfAbsent(handle.id(), handle) != null) return;
            notify = List.copyOf(observers);
        }
        for (var observer : notify) observer.accept(handle);
        handle.completion().whenComplete((result, failure) -> ended(handle, result, failure));
    }

    private void ended(TaskHandle<DeploymentAgentDeliveryPort.Published> handle,
                       OperationResult<DeploymentAgentDeliveryPort.Published> result, Throwable failure) {
        Pending next;
        synchronized (this) {
            if (failure != null || result == null) {
                blocked = "CURRENT_TASK_COMPLETION_UNCONFIRMED";
                return;
            }
            next = pending.get(handle.id());
            if (next == null) {
                if (!blocked.isEmpty() || !retainedPrivateCopies.isEmpty()) return;
                completion.complete(new Result(root, handle.id(), handles.keySet().stream().filter(id -> !id.equals(handle.id())).toList(), result));
                return;
            }
            if (result.outcome() != TaskResult.FAILED || next.ticket == null || !next.ticket.phase().equals("READY") || cancelled) {
                blocked = cancelled ? "GOAL_CANCELLED" : "ORIGINAL_FAILURE_OR_CLEANUP_UNCONFIRMED";
                closePrivate(next.source);
                return;
            }
            if (!next.submitted.compareAndSet(false, true)) return;
        }
        try {
            next.source.verify();
            var child = submit.child(next);
            synchronized (this) {
                if (cancelled) {
                    next.gate.complete(false);
                    child.requestCancel();
                }
                attach(child);
                next.gate.complete(!cancelled);
            }
        } catch (Exception unknownSubmit) {
            synchronized (this) {
                blocked = "SUCCESSOR_SUBMISSION_UNCONFIRMED: " + unknownSubmit.getClass().getSimpleName();
                next.gate.complete(false);
                closePrivate(next.source);
            }
        }
    }

    private synchronized void closePrivate(DeploymentAgentContinuationSource source) {
        try {
            source.close();
            retainedPrivateCopies.remove(source);
        } catch (Exception uncertain) {
            if (!retainedPrivateCopies.contains(source)) retainedPrivateCopies.add(source);
            blocked = "PRIVATE_SOURCE_CUSTODY_CLEANUP_UNCONFIRMED";
        }
    }

    void releasePrivate(Pending value) {
        closePrivate(value.source);
    }

    static final class Pending {
        final DeploymentAgentContinuationSource source;

        DeploymentAgentContinuation.Ticket ticket;

        final CompletableFuture<Boolean> gate = new CompletableFuture<>();

        final java.util.concurrent.atomic.AtomicBoolean submitted = new java.util.concurrent.atomic.AtomicBoolean();

        Pending(DeploymentAgentContinuationSource source) {
            this.source = source;
        }
    }

    public static final class Worker implements AutoCloseable {
        private final DeploymentAgentGoal goal;

        private final TaskContext task;

        private final Optional<DeploymentAgentContinuation.Intent> predecessor;

        private final DeploymentAgentGoalBudget budget;

        private DeploymentAgentGoalBudget.Scope budgetScope;

        private DeploymentSourceMaterial material;

        private DeploymentRemoteLayout layout;

        private Pending next;

        Worker(DeploymentAgentGoal goal, TaskContext task, Optional<DeploymentAgentContinuation.Intent> predecessor,
               DeploymentAgentGoalBudget budget) {
            this.goal = goal;
            this.task = task;
            this.predecessor = predecessor;
            this.budget = budget;
        }

        Worker open() {
            if (CURRENT.get() != null) throw new IllegalStateException("Goal worker scope already exists");
            budgetScope = budget.open(task);
            CURRENT.set(this);
            return this;
        }

        public Optional<DeploymentAgentContinuation.Intent> predecessor() {
            return predecessor;
        }

        public gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalPolicies.Confirmed approvalPolicy() {
            return goal.approvalPolicy();
        }

        public void approvalPolicy(gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalPolicies.Confirmed next) {
            if (CURRENT.get() != this)
                throw new IllegalStateException("Policy propagation requires its current goal worker");
            synchronized (goal) {
                if (next.revision() < goal.approvalPolicy.revision() || next.revision() > goal.approvalPolicy.revision() + 1
                    || next.revision() == goal.approvalPolicy.revision() && !next.mode().equals(goal.approvalPolicy.mode()))
                    throw new IllegalStateException("Original goal approval revision changed");
                goal.approvalPolicy = next;
            }
        }

        public void bind(DeploymentSourceMaterial material, DeploymentRemoteLayout layout) {
            this.material = Objects.requireNonNull(material);
            this.layout = Objects.requireNonNull(layout);
            if (predecessor.isPresent() && (!material.snapshot().sha256().equals(predecessor.orElseThrow().source().source())
                || !layout.canonical().equals(predecessor.orElseThrow().layout())))
                throw new IllegalStateException("Fresh successor source or layout changed");
        }

        public Prepared prepare(DeploymentPublishResult proposal, String failureObservation) throws Exception {
            if (CURRENT.get() != this || task.cancelToken().requested() || goal.cancelled || next != null)
                throw new IllegalStateException("Current online goal worker cannot reserve another successor");
            var resources = DeploymentAgentContinuation.resources(proposal);
            if (predecessor.isPresent())
                DeploymentAgentContinuation.requireResources(predecessor.orElseThrow(), proposal);
            if (budget.snapshot().remainingResponses() < 1 || budget.snapshot().remainingTools() < 1 || budget.build().remainingSeconds() < 1)
                throw new IllegalStateException("Original goal allowance cannot fund an independent successor");
            var capsule = DeploymentAgentContinuationSource.capture(Objects.requireNonNull(material), goal.request, task);
            return new Prepared(this, capsule, proposal.graph().applicationId(), resources, failureObservation);
        }

        void finished(OperationResult<DeploymentAgentDeliveryPort.Published> result) throws Exception {
            if (next == null) return;
            if (result.outcome() != TaskResult.FAILED || task.cancelToken().requested() || goal.cancelled) {
                goal.journal.blockSuccessor(task, task.id());
                goal.closePrivate(next.source);
                synchronized (goal) {
                    goal.blocked = "ORIGINAL_FAILURE_SETTLEMENT_UNCONFIRMED";
                }
                return;
            }
            try {
                next.ticket = goal.journal.readySuccessor(task, budget.snapshot());
            } catch (Exception uncertain) {
                synchronized (goal) {
                    goal.blocked = "SUCCESSOR_READY_PERSISTENCE_UNCONFIRMED";
                }
                goal.closePrivate(next.source);
            }
        }

        @Override
        public void close() {
            if (CURRENT.get() != this) throw new IllegalStateException("Goal worker scope owner changed");
            if (next != null && (next.ticket == null || !next.ticket.phase().equals("READY")))
                goal.closePrivate(next.source);
            CURRENT.remove();
            budgetScope.close();
        }
    }

    public static final class Prepared implements AutoCloseable {
        private final Worker worker;

        private final DeploymentAgentContinuationSource capsule;

        private final String application;

        private final Map<String, String> resources;

        private final String observed;

        private boolean retained;

        Prepared(Worker worker, DeploymentAgentContinuationSource capsule, String application,
                 Map<String, String> resources, String observed) {
            this.worker = worker;
            this.capsule = capsule;
            this.application = application;
            this.resources = resources;
            this.observed = observed;
        }

        public void accept(DeploymentAgentFailedPublication accepted) throws Exception {
            var goal = worker.goal;
            if (!accepted.execution().taskId().equals(worker.task.id()) || !accepted.source().equals(capsule.source())
                || !accepted.execution().applicationId().equals(application))
                throw new IllegalStateException("Current failure and source custody differ");
            var info = goal.context.servers().get(goal.server, worker.task);
            var intent = new DeploymentAgentContinuation.Intent(goal.root, worker.task.id(), UUID.randomUUID(), UUID.randomUUID(),
                worker.predecessor.map(value -> value.ordinal() + 1).orElse(2), application, goal.server.id(), accepted.identity().targetDigest(),
                accepted.identity().configurationDigest(), DeploymentAgentInputs.targetProfile(info), DeploymentAgentInputs.physicalTarget(info),
                worker.layout.canonical(), worker.layout.digest(), goal.request, DeploymentAgentContinuation.Source.capture(capsule), resources,
                accepted.detail(), observed, worker.budget.snapshot());
            var pending = new Pending(capsule);
            pending.ticket = goal.journal.reserveSuccessor(worker.task, intent);
            synchronized (goal) {
                goal.pending.put(worker.task.id(), pending);
                worker.next = pending;
            }
            retained = true;
        }

        @Override
        public void close() {
            if (!retained) worker.goal.closePrivate(capsule);
        }
    }
}
