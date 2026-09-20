package gold.debug.windowstolinux.web.task.scheduler;

import tools.jackson.databind.JsonNode;
import gold.debug.windowstolinux.web.db.entity.ResourceScope;
import gold.debug.windowstolinux.web.db.entity.StoredTask;
import gold.debug.windowstolinux.web.db.persistence.repository.WebTaskRepository;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.web.task.model.TaskState;
import gold.debug.windowstolinux.web.task.model.WebTaskPolicy;
import org.springframework.dao.DataAccessException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded background work, target exclusion, durable decisions and conservative restart recovery. */
public final class WebTaskScheduler implements AutoCloseable {
    private static final Set<String> ACTIVE = Set.of("QUEUED", "ANALYZING", "RUNNING", "WAITING_DECISION", "CANCELLING");
    private final WebTaskRepository repository;
    private final ExecutorService workers;
    private final Map<String, RunningTask> running = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.locks.ReentrantLock> locks = new ConcurrentHashMap<>();
    private final WebTaskPolicy policy;
    private boolean closed;

    public WebTaskScheduler(WebTaskRepository repository, WebTaskPolicy policy) {
        this.repository = repository; this.policy = policy;
        repository.recoverInterrupted();
        workers = Executors.newFixedThreadPool(policy.workers(), Thread.ofPlatform().name("web-task-", 0).factory());
    }

    public synchronized String submit(WebRequestContext context, PreparedWebOperation operation) {
        if (closed || running.size() >= policy.maxActive()) throw new IllegalStateException("Task queue is unavailable or full");
        String id = UUID.randomUUID().toString(); ResourceScope scope = scope(context);
        repository.create(scope, id, operation.kind(), WebJson.write(operation.request()), operation.serverIds(), operation.mutating(),
                operation.sourceId(), operation.applicationId(), operation.backupId());
        var task = new RunningTask(scope, id); running.put(key(scope, id), task);
        task.future = workers.submit(() -> execute(task, operation)); return id;
    }

    public JsonNode list(WebRequestContext context) {
        var result = new ArrayList<JsonNode>();
        for (var row : repository.list(scope(context))) result.add(snapshot(scope(context), row));
        return WebJson.tree(result);
    }
    public JsonNode get(WebRequestContext context, String id) { return snapshot(scope(context), require(scope(context), id)); }
    public JsonNode events(WebRequestContext context, String id, long after) {
        ResourceScope scope = scope(context); require(scope, id);
        return WebJson.tree(repository.events(scope, id, after).stream().map(event -> Map.of("sequence", event.sequence(), "kind", event.kind(),
                "message", event.message(), "details", WebJson.read(event.detailJson()), "createdAt", event.createdAt())).toList());
    }

    public void cancel(WebRequestContext context, String id) {
        ResourceScope scope = scope(context); StoredTask row = require(scope, id);
        if (TaskState.valueOf(row.state()).terminal()) return;
        RunningTask task = running.get(key(scope, id));
        if (task == null) throw new IllegalStateException("Task requires revalidation");
        task.cancelled.set(true);
        repository.transition(scope, id, Set.of("QUEUED", "ANALYZING", "RUNNING", "WAITING_DECISION"), "CANCELLING", null, null);
        synchronized (task) {
            task.notifyAll();
            if (task.thread != null) task.thread.interrupt();
        }
    }

    public void answer(WebRequestContext context, String taskId, String decisionId, JsonNode answer) {
        ResourceScope scope = scope(context); require(scope, taskId);
        RunningTask task = running.get(key(scope, taskId));
        if (task == null || answer == null || !answer.isObject() || WebJson.write(answer).length() > 32768)
            throw new IllegalArgumentException("Decision unavailable or invalid");
        synchronized (task) {
            validateAnswer(task.decisionKind, task.decisionPrompt, answer);
            if (!decisionId.equals(task.decisionId) || task.answer != null
                    || !repository.answer(scope, taskId, decisionId, WebJson.write(answer), Instant.now()))
                throw new IllegalStateException("Decision expired or already answered");
            task.answer = answer.deepCopy(); task.notifyAll();
        }
    }

    private static void validateAnswer(String kind, JsonNode prompt, JsonNode answer) {
        if (kind == null) throw new IllegalStateException("No pending decision");
        switch (kind) {
            case "HOST_KEY", "CONFIRM" -> {
                WebJson.fields(answer, "accepted");
                if (!answer.path("accepted").isBoolean()) throw new IllegalArgumentException("Expected boolean decision");
            }
            case "SECRET" -> {
                WebJson.fields(answer, "secretId");
                ResourceScope.identifier(WebJson.text(answer, "secretId", 64));
            }
            case "DATABASE_REPLACEMENT" -> {
                WebJson.fields(answer, "backupConfirmed", "downtimeConfirmed", "replacementConfirmed");
                for (String field : List.of("backupConfirmed", "downtimeConfirmed", "replacementConfirmed"))
                    if (!answer.path(field).isBoolean()) throw new IllegalArgumentException("Expected explicit boolean acknowledgements");
            }
            case "INPUTS" -> {
                WebJson.fields(answer, "values");
                var names = new ArrayList<String>();
                for (var field : prompt.path("fields")) names.add(field.path("id").asText());
                var values = answer.path("values"); WebJson.fields(values, names.toArray(String[]::new));
                WebTaskPrompts.validateNonSecretInputs(values);
                for (var field : prompt.path("fields")) {
                    var value=values.path(field.path("id").asText());
                    if (!value.isTextual() || value.asText().length()>4096 || value.asText().indexOf('\0')>=0)
                        throw new IllegalArgumentException("Invalid field value");
                    if (!field.path("choices").isEmpty()) {
                        boolean allowed=false;
                        for (var choice : field.path("choices")) if (choice.equals(value)) allowed=true;
                        if (!allowed) throw new IllegalArgumentException("Unexpected field choice");
                    }
                }
            }
            default -> throw new IllegalArgumentException("Unsupported decision");
        }
    }

    private void execute(RunningTask task, PreparedWebOperation operation) {
        var held = new ArrayList<java.util.concurrent.locks.ReentrantLock>();
        synchronized (task) { task.thread = Thread.currentThread(); }
        try {
            task.checkCancelled();
            if (operation.mutating()) for (String target : operation.lockKeys().stream().distinct().sorted().toList()) {
                var lock = locks.computeIfAbsent(target, ignored -> new java.util.concurrent.locks.ReentrantLock(true));
                lock.lockInterruptibly(); held.add(lock); task.checkCancelled();
            }
            if (!repository.transition(task.scope, task.id, Set.of("QUEUED"), "RUNNING", null, null)) task.checkCancelled();
            JsonNode result = operation.work().execute(task);
            task.checkCancelled();
            repository.transition(task.scope, task.id, Set.of("RUNNING", "ANALYZING"), task.completion.name(), WebJson.write(result),
                    task.completion == OperationCompletionState.SUCCEEDED ? null : "BUSINESS_OPERATION_FAILED");
        } catch (Exception failure) {
            boolean interrupted = task.cancelled.get() || Thread.currentThread().isInterrupted() || failure instanceof InterruptedException || failure instanceof CancellationException;
            Thread.interrupted();
            try {
                // Interrupted mutations need a fresh remote observation before another attempt.
                String state = task.completion == OperationCompletionState.REVALIDATION_REQUIRED ? "REVALIDATION_REQUIRED"
                        : interrupted ? (operation.mutating() ? "REVALIDATION_REQUIRED" : "CANCELLED") : "FAILED";
                String code = interrupted ? "OPERATION_CANCELLED" : failure instanceof TimeoutException ? "DECISION_EXPIRED"
                        : failure instanceof IllegalArgumentException ? "INPUT_REJECTED" : "OPERATION_FAILED";
                repository.transition(task.scope, task.id, ACTIVE, state, null, code);
            } catch (DataAccessException persistenceFailure) { System.err.println("Web task state could not be persisted; restart revalidation required."); }
        } finally {
            for (int i = held.size() - 1; i >= 0; i--) held.get(i).unlock();
            running.remove(key(task.scope, task.id));
            synchronized (task) { task.thread = null; }
        }
    }

    private StoredTask require(ResourceScope scope, String id) {
        return repository.find(scope, id).orElseThrow(() -> new NoSuchElementException("Task not found"));
    }
    private JsonNode snapshot(ResourceScope scope, StoredTask task) {
        var json = WebJson.object().put("id", task.id()).put("kind", task.kind()).put("state", task.state())
                .put("createdAt", task.createdAt()).put("updatedAt", task.updatedAt()).put("errorCode", task.errorCode());
        json.set("result", task.resultJson() == null ? null : WebJson.read(task.resultJson()));
        var decision = repository.pendingDecision(scope, task.id());
        if (!decision.isEmpty()) json.set("decision", WebJson.object().put("id", decision.get("id")).put("kind", decision.get("kind"))
                .put("expiresAt", decision.get("expiresAt")).set("prompt", WebJson.read(decision.get("prompt"))));
        return json;
    }
    private static ResourceScope scope(WebRequestContext context) { return new ResourceScope(context.workspaceId(), context.userId()); }
    private static String key(ResourceScope scope, String id) { return scope.workspaceId() + "/" + id; }

    @Override public void close() {
        synchronized (this) { closed = true; }
        for (var task : running.values()) {
            task.cancelled.set(true);
            synchronized (task) { task.notifyAll(); if (task.thread != null) task.thread.interrupt(); }
        }
        workers.shutdown();
        try {
            if (!workers.awaitTermination(policy.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                workers.shutdownNow();
                if (!workers.awaitTermination(policy.forcedShutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) throw new IllegalStateException("Web tasks did not stop");
            }
        } catch (InterruptedException interrupted) { workers.shutdownNow(); Thread.currentThread().interrupt(); }
    }

    private final class RunningTask implements TaskInteraction {
        private final ResourceScope scope; private final String id; private final AtomicBoolean cancelled = new AtomicBoolean();
        private Future<?> future; private Thread thread; private String decisionId; private JsonNode answer; private int events;
        private String decisionKind; private JsonNode decisionPrompt;
        private OperationCompletionState completion = OperationCompletionState.SUCCEEDED;
        private RunningTask(ResourceScope scope, String id) { this.scope = scope; this.id = id; }
        @Override public void completion(OperationCompletionState state) { completion = Objects.requireNonNull(state); }
        @Override public void checkCancelled() throws InterruptedException {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException("Task cancelled");
        }
        @Override public void progress(String code, JsonNode details) throws Exception {
            checkCancelled();
            if (!code.matches("[A-Za-z0-9_.:-]{1,120}") || ++events > policy.maxEvents()) throw new IllegalArgumentException("Invalid task progress");
            repository.event(scope, id, "PROGRESS", code, WebJson.write(details));
        }
        @Override public synchronized JsonNode decide(String kind, JsonNode prompt) throws Exception {
            checkCancelled(); decisionId = UUID.randomUUID().toString(); answer = null;
            decisionKind=kind; decisionPrompt=prompt.deepCopy();
            Instant expires = Instant.now().plus(policy.decisionTimeout());
            repository.createDecision(scope, id, decisionId, kind, WebJson.write(prompt), expires);
            if (!repository.transition(scope, id, Set.of("RUNNING", "ANALYZING"), "WAITING_DECISION", null, null)) {
                checkCancelled(); throw new IllegalStateException("Task cannot ask for a decision");
            }
            while (answer == null) {
                checkCancelled(); long remaining = Duration.between(Instant.now(), expires).toMillis();
                if (remaining <= 0) throw new TimeoutException("Decision expired");
                wait(Math.min(1000, remaining));
            }
            checkCancelled();
            if (!repository.transition(scope, id, Set.of("WAITING_DECISION"), "RUNNING", null, null)) {
                checkCancelled(); throw new IllegalStateException("Task cannot continue");
            }
            return answer.deepCopy();
        }
    }
}
