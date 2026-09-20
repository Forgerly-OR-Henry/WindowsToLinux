package gold.debug.windowstolinux.web.db.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import gold.debug.windowstolinux.web.db.entity.*;
import gold.debug.windowstolinux.web.db.persistence.mapper.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** State transitions and their event journal share one Spring transaction. */
@Repository
@Transactional
public class WebTaskRepository {
    private static final Set<String> TERMINAL = Set.of("CANCELLED", "SUCCEEDED", "FAILED", "INTERRUPTED", "REVALIDATION_REQUIRED");
    private static final Set<String> ACTIVE = Set.of("QUEUED", "ANALYZING", "RUNNING", "WAITING_DECISION", "CANCELLING");
    private final ScopeAccess access;
    private final WebTaskMapper tasks;
    private final WebTaskTargetMapper targets;
    private final WebTaskDecisionMapper decisions;
    private final WebTaskJournalMapper journal;

    public WebTaskRepository(ScopeAccess access, WebTaskMapper tasks, WebTaskTargetMapper targets,
                             WebTaskDecisionMapper decisions, WebTaskJournalMapper journal) {
        this.access = access; this.tasks = tasks; this.targets = targets; this.decisions = decisions; this.journal = journal;
    }

    public void create(ResourceScope scope, String id, String kind, String request, List<String> servers,
                       boolean mutating, String sourceId, String applicationId, String backupId) {
        access.require(scope); ResourceScope.identifier(id); ResourceScope.identifier(kind);
        String now = Instant.now().toString();
        tasks.insert(new WebTaskEntity(scope.workspaceId(), id, scope.userId(), kind, "QUEUED", request, null,
                sourceId, applicationId, backupId, null, now, now, null));
        for (String server : new LinkedHashSet<>(servers))
            targets.insert(new WebTaskTargetEntity(scope.workspaceId(), id, server, mutating ? 1 : 0));
        journal.append(scope, id, "STATE", "QUEUED", "{}", now);
    }

    public Optional<StoredTask> find(ResourceScope scope, String id) {
        access.require(scope); ResourceScope.identifier(id);
        return Optional.ofNullable(tasks.selectOne(new QueryWrapper<WebTaskEntity>()
                .eq("workspace_id", scope.workspaceId()).eq("id", id))).map(WebTaskEntity::stored);
    }

    public List<StoredTask> list(ResourceScope scope) {
        access.require(scope);
        return tasks.selectList(new QueryWrapper<WebTaskEntity>().eq("workspace_id", scope.workspaceId())
                .orderByDesc("created_at").orderByAsc("id").last("LIMIT 200")).stream().map(WebTaskEntity::stored).toList();
    }

    public boolean transition(ResourceScope scope, String id, Set<String> from, String to, String result, String error) {
        access.require(scope);
        if (from.isEmpty() || from.stream().anyMatch(TERMINAL::contains)) throw new IllegalArgumentException("Cannot alter terminal task");
        String now = Instant.now().toString();
        if (tasks.transition(scope, id, from, to, result, error, now, TERMINAL.contains(to) ? now : null) != 1) return false;
        journal.append(scope, id, "STATE", to, "{}", now);
        return true;
    }

    public void event(ResourceScope scope, String id, String kind, String message, String details) {
        access.require(scope);
        if (message.length() > 4096 || details.length() > 32768) throw new IllegalArgumentException("Oversized task event");
        String now = Instant.now().toString();
        journal.append(scope, id, kind, message, details, now);
        if (kind.equals("PROGRESS")) journal.step(scope, id, message, details, now);
    }

    public List<StoredTaskEvent> events(ResourceScope scope, String id, long after) {
        access.require(scope);
        if (after < 0) throw new IllegalArgumentException("Invalid event cursor");
        return journal.events(scope, id, after);
    }

    public void createDecision(ResourceScope scope, String task, String id, String kind, String prompt, Instant expires) {
        access.require(scope);
        decisions.insert(new WebTaskDecisionEntity(scope.workspaceId(), task, id, kind, prompt, null, expires.toString(), null, null));
    }

    public boolean answer(ResourceScope scope, String task, String id, String answer, Instant now) {
        access.require(scope);
        return decisions.answer(scope, task, id, answer, now.toString()) == 1;
    }

    public Map<String,String> pendingDecision(ResourceScope scope, String task) {
        access.require(scope);
        Map<String,String> pending = decisions.pending(scope, task);
        return pending == null ? Map.of() : Map.copyOf(pending);
    }

    public int recoverInterrupted() {
        String now = Instant.now().toString();
        var unfinished = tasks.selectList(new QueryWrapper<WebTaskEntity>().in("state", ACTIVE));
        var workspaces = new HashSet<>(tasks.interruptedApplicationWorkspaces());
        for (var task : unfinished) {
            var scope = new ResourceScope(task.workspaceId(), task.createdBy());
            int changed = tasks.transition(scope, task.id(), ACTIVE, "REVALIDATION_REQUIRED", null, "BACKEND_RESTARTED", now, now);
            if (changed != 1) throw new IllegalStateException("Task changed during startup recovery");
            journal.append(scope, task.id(), "STATE", "REVALIDATION_REQUIRED", "{}", now);
            workspaces.add(task.workspaceId());
        }
        for (String workspace : workspaces) tasks.recoverApplications(workspace, now);
        return unfinished.size();
    }
}
