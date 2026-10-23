package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.action.CommandReviewMode;
import gold.debug.windowstolinux.appapi.task.action.WorkflowGrant;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument;
import gold.debug.windowstolinux.mod.deployment.db.setting.DeploymentPreferenceStore;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentApprovalPolicyState;

/** Queues UI intent; only the authentic worker changes its existing host scope at the next review boundary. */
public final class DeploymentApprovalPolicies {
    public static final String KEY = "deployment-approval-policy";

    private static final Map<UUID, Controller> LIVE = new ConcurrentHashMap<>();

    public record Confirmed(String mode, long revision) {
        public Confirmed {
            DeploymentAuthorizationPolicy.requireAllowed(mode, true);
            if (revision < 0) throw new IllegalArgumentException("Negative approval revision");
        }
    }

    /** Restores confirmed evidence only; an interrupted change is never replayed or guessed. */
    public static Confirmed original(ModContext context, TaskContext task, String initial) throws ApiException {
        return original(context, task, initial, 0);
    }

    public static Confirmed original(ModContext context, TaskContext task, String initial, long revision) throws ApiException {
        var checkpoint = context.tasks().findCheckpoint(task, KEY);
        if (checkpoint.isEmpty()) return new Confirmed(initial, revision);
        var value = read(context, task, checkpoint.orElseThrow().snapshot().payload());
        if (Set.of("REQUESTED", "APPLYING", "UNKNOWN").contains(value.path("phase").asText()))
            throw failure(task.id(), ProblemCode.UNKNOWN, "DEPLOYMENT_APPROVAL_POLICY_UNCONFIRMED");
        return new Confirmed(value.path("mode").asText(), value.path("revision").longValue());
    }

    public static Controller open(ModContext context, TaskContext task, WorkflowGrant permission,
                                  Confirmed confirmed, boolean remember, boolean recovery) throws ApiException {
        var existing = context.tasks().findCheckpoint(task, KEY);
        var checkpoint = existing.isPresent() ? existing.orElseThrow() : recovery ? null
            : context.tasks().beginCheckpoint(task, KEY, document(context, task, confirmed, remember).toString());
        var value = checkpoint == null ? document(context, task, confirmed, remember) : read(context, task, checkpoint.snapshot().payload());
        if (!value.path("mode").asText().equals(confirmed.mode()) || value.path("revision").longValue() != confirmed.revision()
            || value.path("rememberAuthorization").booleanValue() != remember)
            throw failure(task.id(), ProblemCode.UNKNOWN, "DEPLOYMENT_APPROVAL_POLICY_IDENTITY_CHANGED");
        var controller = new Controller(context, task, permission, checkpoint, confirmed, remember,
            !recovery && checkpoint != null && !checkpoint.snapshot().released());
        var retained = LIVE.get(task.id());
        if (recovery && retained != null && !retained.state().editable()) LIVE.remove(task.id(), retained);
        if (LIVE.putIfAbsent(task.id(), controller) != null)
            throw failure(task.id(), ProblemCode.ACCESS_DENIED, "DEPLOYMENT_APPROVAL_POLICY_ALREADY_ACTIVE");
        return controller;
    }

    /** Checks immutable repetition before asking the host to reopen any authority. */
    public static void requireRemember(ModContext context, TaskContext task, boolean remember) throws ApiException {
        var existing = context.tasks().findCheckpoint(task, KEY);
        if (existing.isPresent() && read(context, task, existing.orElseThrow().snapshot().payload())
            .path("rememberAuthorization").booleanValue() != remember)
            throw failure(task.id(), ProblemCode.UNKNOWN, "DEPLOYMENT_APPROVAL_POLICY_IDENTITY_CHANGED");
    }

    public static void request(ModContext owner, UUID original, String target, long expectedRevision) throws ApiException {
        DeploymentAuthorizationPolicy.requireAllowed(target, true);
        var controller = LIVE.get(original);
        if (controller == null || controller.context != owner)
            throw failure(original, ProblemCode.ACCESS_DENIED, "DEPLOYMENT_APPROVAL_POLICY_NOT_ACTIVE");
        controller.request(target, expectedRevision);
    }

    public static DeploymentApprovalPolicyState state(ModContext owner, UUID original) {
        var controller = LIVE.get(original);
        return controller == null || controller.context != owner
            ? new DeploymentApprovalPolicyState("MANUAL_REVIEW", 0, Optional.empty(), Optional.empty(), false)
            : controller.state();
    }

    /** Returns the last confirmed value without consuming a new UI request during an in-progress review. */
    public static String current(TaskContext task, String initial) {
        var controller = LIVE.get(task.id());
        return controller == null || controller.task != task ? initial : controller.confirmed().mode();
    }

    public static String boundary(TaskContext task, String initial) throws ApiException {
        var controller = LIVE.get(task.id());
        return controller == null || controller.task != task ? initial : controller.boundary();
    }

    /** A settled reconciliation may release the policy occupancy without changing its retained evidence. */
    public static void releaseKnown(ModContext context, TaskContext task) throws ApiException {
        original(context, task, "MANUAL_REVIEW");
        var checkpoint = context.tasks().findCheckpoint(task, KEY);
        if (checkpoint.isPresent() && !checkpoint.orElseThrow().snapshot().released()) checkpoint.orElseThrow().close();
    }

    public static final class Controller implements AutoCloseable {
        private final ModContext context;

        private final TaskContext task;

        private final WorkflowGrant permission;

        private final TaskCheckpoint checkpoint;

        private final boolean remember;

        private final Thread worker = Thread.currentThread();

        private Confirmed confirmed;

        private long hostRevision;

        private String pending;

        private String problem;

        private boolean editable;

        private boolean blocked;

        private boolean hostCloseUnconfirmed;

        private String executionMode = "ASSISTED";

        private java.util.function.Consumer<Confirmed> observer = ignored -> {
        };

        private Controller(ModContext context, TaskContext task, WorkflowGrant permission, TaskCheckpoint checkpoint,
                           Confirmed confirmed, boolean remember, boolean editable) {
            this.context = context;
            this.task = task;
            this.permission = permission;
            this.checkpoint = checkpoint;
            this.confirmed = confirmed;
            this.remember = remember;
            this.editable = editable;
        }

        /** Installs product-owned continuation propagation; it cannot grant or select another task. */
        public void observe(String executionMode, java.util.function.Consumer<Confirmed> observer) {
            if (Thread.currentThread() != worker || !Set.of("ASSISTED", "AGENT").contains(executionMode))
                throw new IllegalStateException("Approval observer belongs to its original worker");
            synchronized (this) {
                this.executionMode = executionMode;
                this.observer = Objects.requireNonNull(observer);
            }
            observer.accept(confirmed);
        }

        public synchronized Confirmed confirmed() {
            return confirmed;
        }

        public synchronized DeploymentApprovalPolicyState state() {
            return new DeploymentApprovalPolicyState(confirmed.mode(), confirmed.revision(), Optional.ofNullable(pending),
                Optional.ofNullable(problem), editable && !blocked);
        }

        private synchronized void request(String target, long expected) throws ApiException {
            if (!editable || blocked || pending != null || expected != confirmed.revision())
                throw failure(task.id(), ProblemCode.ACCESS_DENIED, "DEPLOYMENT_APPROVAL_POLICY_STALE_OR_BUSY");
            if (target.equals(confirmed.mode())) return;
            pending = target;
            problem = null;
        }

        private String boundary() throws ApiException {
            final String selected;
            final Confirmed before;
            synchronized (this) {
                if (Thread.currentThread() != worker)
                    throw failure(task.id(), ProblemCode.ACCESS_DENIED, "DEPLOYMENT_APPROVAL_POLICY_WRONG_WORKER");
                if (blocked || hostCloseUnconfirmed)
                    throw failure(task.id(), ProblemCode.UNKNOWN, "DEPLOYMENT_APPROVAL_POLICY_UNCONFIRMED");
                selected = pending;
                before = confirmed;
                if (selected == null) return before.mode();
            }
            // Do not hold the UI state monitor across the host's human FULL_CONTROL question.
            try {
                persist("REQUESTED", selected, before);
                persist("APPLYING", selected, before);
                final OperationResult<gold.debug.windowstolinux.appapi.task.action.WorkflowPolicyState> result;
                try {
                    result = context.interaction().updateReviewedWorkflowPolicy(permission, hostRevision,
                        CommandReviewMode.valueOf(selected), task);
                } catch (ApiException rejected) {
                    if (rejected.problem().code() == ProblemCode.UNKNOWN) throw rejected;
                    persist("REJECTED", selected, before);
                    synchronized (this) {
                        pending = null;
                        problem = rejected.problem().eventCode();
                    }
                    return before.mode();
                }
                if (result.outcome() == TaskResult.UNKNOWN) throw failure(task.id(), ProblemCode.UNKNOWN,
                    "DEPLOYMENT_APPROVAL_POLICY_UNCONFIRMED");
                if (result.outcome() != TaskResult.SUCCEEDED) {
                    persist("REJECTED", selected, before);
                    synchronized (this) {
                        pending = null;
                        problem = result.problem().map(Problem::eventCode).orElse("DEPLOYMENT_APPROVAL_POLICY_REJECTED");
                    }
                    return before.mode();
                }
                var applied = result.value().orElseThrow();
                if (!applied.mode().name().equals(selected) || applied.revision() != hostRevision + 1)
                    throw failure(task.id(), ProblemCode.UNKNOWN, "DEPLOYMENT_APPROVAL_POLICY_RESPONSE_CHANGED");
                var next = new Confirmed(selected, Math.addExact(before.revision(), 1));
                persist("CONFIRMED", selected, next);
                synchronized (this) {
                    confirmed = next;
                    hostRevision = applied.revision();
                    pending = null;
                }
                observer.accept(next);
                try {
                    new DeploymentPreferenceStore(context.db()).save(task,
                        new DeploymentPreferenceStore.Selection(executionMode, selected, remember));
                } catch (Exception preferenceFailure) {
                    synchronized (this) {
                        problem = "DEPLOYMENT_APPROVAL_PREFERENCE_SAVE_FAILED";
                    }
                    try {
                        task.progress("Approval policy applied; saving the default preference failed", Optional.empty());
                    } catch (
                        ApiException unavailableProgress) { /* The confirmed policy is independent of progress delivery. */ }
                }
                return next.mode();
            } catch (Exception uncertain) {
                synchronized (this) {
                    blocked = true;
                    editable = false;
                    problem = "DEPLOYMENT_APPROVAL_POLICY_UNCONFIRMED";
                }
                // The durable REQUESTED/APPLYING payload deliberately remains unresolved.
                throw failure(task.id(), ProblemCode.UNKNOWN, "DEPLOYMENT_APPROVAL_POLICY_UNCONFIRMED");
            }
        }

        private void persist(String phase, String selected, Confirmed state) throws ApiException {
            var before = checkpoint.snapshot();
            var value = read(context, task, before.payload());
            value.put("mode", state.mode()).put("revision", state.revision()).put("phase", phase).put("requestedMode", selected);
            checkpoint.advance(before, value.toString());
        }

        @Override
        public void close() throws ApiException {
            synchronized (this) {
                editable = false;
            }
            if (!blocked && checkpoint != null && !checkpoint.snapshot().released()) checkpoint.close();
            LIVE.remove(task.id(), this);
        }

        /** A failed opaque host close cannot prove that task authority or its policy resource is released. */
        public synchronized void hostCloseFailed() {
            editable = false;
            hostCloseUnconfirmed = true;
            problem = "DEPLOYMENT_APPROVAL_POLICY_SCOPE_CLOSE_UNCONFIRMED";
        }
    }

    public static Optional<Controller> controller(TaskContext task) {
        var controller = LIVE.get(task.id());
        return controller != null && controller.task == task ? Optional.of(controller) : Optional.empty();
    }

    private static ObjectNode document(ModContext context, TaskContext task, Confirmed state, boolean remember) {
        return DeploymentJsonDocument.object().put("formatVersion", 1).put("task", task.id().toString())
            .put("owner", context.identity().id()).put("ownerVersion", context.identity().version())
            .put("mode", state.mode()).put("revision", state.revision()).put("rememberAuthorization", remember)
            .put("phase", "CONFIRMED").put("requestedMode", state.mode());
    }

    private static ObjectNode read(ModContext context, TaskContext task, String payload) throws ApiException {
        try {
            var value = DeploymentJsonDocument.parse(payload, 8192);
            if (value.size() != 9 || value.path("formatVersion").asInt() != 1
                || !task.id().toString().equals(value.path("task").asText()) || !context.identity().id().equals(value.path("owner").asText())
                || !context.identity().version().equals(value.path("ownerVersion").asText()) || !value.path("revision").isIntegralNumber()
                || !value.path("revision").canConvertToLong()
                || !value.path("rememberAuthorization").isBoolean()
                || !Set.of("CONFIRMED", "REQUESTED", "APPLYING", "REJECTED", "UNKNOWN").contains(value.path("phase").asText()))
                throw new IllegalArgumentException("Original approval evidence changed");
            new Confirmed(value.path("mode").asText(), value.path("revision").longValue());
            DeploymentAuthorizationPolicy.requireAllowed(value.path("requestedMode").asText(), true);
            return value;
        } catch (Exception invalid) {
            throw failure(task.id(), ProblemCode.UNKNOWN, "DEPLOYMENT_APPROVAL_POLICY_EVIDENCE_INVALID");
        }
    }

    private static ApiException failure(UUID task, ProblemCode code, String event) {
        return new ApiException(new Problem(code, event, "Original task approval policy requires confirmation",
            Map.of(), Optional.of(task)));
    }

    private DeploymentApprovalPolicies() {
    }
}
