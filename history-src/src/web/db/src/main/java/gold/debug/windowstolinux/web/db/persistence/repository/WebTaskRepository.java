package gold.debug.windowstolinux.web.db.persistence.repository;

import java.time.Instant;
import java.util.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import gold.debug.windowstolinux.web.db.entity.*;
import gold.debug.windowstolinux.web.db.persistence.mapper.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps task state transitions and their event journal in one Spring transaction.
 * <p>将任务状态转换及其事件日志保留在同一个 Spring 事务中。
 */
@Repository
@Transactional
public class WebTaskRepository {
    /**
     * TERMINAL.
     * <p>终端。
     */
    private static final Set<String> TERMINAL = Set.of("CANCELLED", "SUCCEEDED", "FAILED", "INTERRUPTED",
            "REVALIDATION_REQUIRED");

    /**
     * ACTIVE.
     * <p>活跃。
     */
    private static final Set<String> ACTIVE = Set.of("QUEUED", "ANALYZING", "RUNNING", "WAITING_DECISION",
            "CANCELLING");

    /**
     * Access mode.
     * <p>访问模式。
     */
    private final ScopeAccess access;

    /**
     * Tasks.
     * <p>任务集合。
     */
    private final WebTaskMapper tasks;

    /**
     * Targets.
     * <p>目标集合。
     */
    private final WebTaskTargetMapper targets;

    /**
     * Number of recovery decisions consumed in the current active budget.
     * <p>当前活跃预算内已消耗的救援决策次数。
     */
    private final WebTaskDecisionMapper decisions;

    /**
     * Journal.
     * <p>日志。
     */
    private final WebTaskJournalMapper journal;

    /**
     * Binds the supplied dependencies and state for web task repository.
     * <p>为Web任务仓库绑定传入的依赖及状态。
     *
     * @param access access mode / 访问模式
     * @param tasks tasks / 任务集合
     * @param targets targets / 目标集合
     * @param decisions number of recovery decisions consumed in the current active budget / 当前活跃预算内已消耗的救援决策次数
     * @param journal journal / 日志
     */
    public WebTaskRepository(ScopeAccess access, WebTaskMapper tasks, WebTaskTargetMapper targets,
            WebTaskDecisionMapper decisions, WebTaskJournalMapper journal) {
        this.access = access;
        this.tasks = tasks;
        this.targets = targets;
        this.decisions = decisions;
        this.journal = journal;
    }

    /**
     * Accepts the callback without side effects because this adapter needs no additional action.
     * <p>接受回调且不产生副作用，因为当前适配器无需额外动作。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param servers server-profile and authenticated-session service / 服务器资料及已认证会话服务
     * @param mutating mutating / 变更
     * @param sourceId source id / 源码标识
     * @param applicationId managed application identifier / 受管应用标识
     * @param backupId backup id / 备份标识
     */
    public void create(ResourceScope scope, String id, String kind, String request, List<String> servers,
            boolean mutating, String sourceId, String applicationId, String backupId) {
        access.require(scope);
        ResourceScope.identifier(id);
        ResourceScope.identifier(kind);
        String now = Instant.now().toString();
        tasks.insert(new WebTaskEntity(scope.workspaceId(), id, scope.userId(), kind, "QUEUED", request, null, sourceId,
                applicationId, backupId, null, now, now, null));
        for (String server : new LinkedHashSet<>(servers))
            targets.insert(new WebTaskTargetEntity(scope.workspaceId(), id, server, mutating ? 1 : 0));
        journal.append(scope, id, "STATE", "QUEUED", "{}", now);
    }

    /**
     * Finds optional.
     * <p>查找可选。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    public Optional<StoredTask> find(ResourceScope scope, String id) {
        access.require(scope);
        ResourceScope.identifier(id);
        return Optional
                .ofNullable(tasks.selectOne(
                        new QueryWrapper<WebTaskEntity>().eq("workspace_id", scope.workspaceId()).eq("id", id)))
                .map(WebTaskEntity::stored);
    }

    /**
     * Lists list.
     * <p>列出列表。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @return constructed or resolved list / 构造或解析得到的列表
     */
    public List<StoredTask> list(ResourceScope scope) {
        access.require(scope);
        return tasks
                .selectList(new QueryWrapper<WebTaskEntity>().eq("workspace_id", scope.workspaceId())
                        .orderByDesc("created_at").orderByAsc("id").last("LIMIT 200"))
                .stream().map(WebTaskEntity::stored).toList();
    }

    /**
     * Tests the transition predicate against the supplied evidence.
     * <p>根据所提供证据检查转换条件。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param from from / 来源
     * @param to the supplied string / 所提供的字符串
     * @param result typed outcome produced by the delegated operation / 被委派操作产生的类型化结果
     * @param error error / 错误
     * @return true when transition predicate against the supplied evidence, false otherwise / 根据所提供证据检查转换条件时为 true，否则为 false
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public boolean transition(ResourceScope scope, String id, Set<String> from, String to, String result,
            String error) {
        access.require(scope);
        if (from.isEmpty() || from.stream().anyMatch(TERMINAL::contains))
            throw new IllegalArgumentException("Cannot alter terminal task");
        String now = Instant.now().toString();
        if (tasks.transition(scope, id, from, to, result, error, now, TERMINAL.contains(to) ? now : null) != 1)
            return false;
        journal.append(scope, id, "STATE", to, "{}", now);
        return true;
    }

    /**
     * Checks event syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查事件语法及边界。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
     * @param message localized explanation / 本地化说明
     * @param details details / 详情
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public void event(ResourceScope scope, String id, String kind, String message, String details) {
        access.require(scope);
        if (message.length() > 4096 || details.length() > 32768)
            throw new IllegalArgumentException("Oversized task event");
        String now = Instant.now().toString();
        journal.append(scope, id, kind, message, details, now);
        if (kind.equals("PROGRESS"))
            journal.step(scope, id, message, details, now);
    }

    /**
     * Reads recorded events in the order required by the caller's cursor.
     * <p>按调用方游标要求的顺序读取已记录事件。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param after after / 之后
     * @return recorded events in the order required by the caller's cursor / 按调用方游标要求的顺序读取已记录事件
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public List<StoredTaskEvent> events(ResourceScope scope, String id, long after) {
        access.require(scope);
        if (after < 0)
            throw new IllegalArgumentException("Invalid event cursor");
        return journal.events(scope, id, after);
    }

    /**
     * Creates decision.
     * <p>创建决定。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param task task / 任务
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
     * @param prompt prompt / 提示
     * @param expires expires / 到期
     */
    public void createDecision(ResourceScope scope, String task, String id, String kind, String prompt,
            Instant expires) {
        access.require(scope);
        decisions.insert(new WebTaskDecisionEntity(scope.workspaceId(), task, id, kind, prompt, null,
                expires.toString(), null, null));
    }

    /**
     * Records the answer for web task.
     * <p>记录以下交互的回答：Web任务。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param task task / 任务
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param answer answer / 回答
     * @param now current instant supplied by the caller or clock / 调用方或时钟提供的当前时刻
     * @return true when records the answer for web task, false otherwise / 记录以下交互的回答：Web任务时为 true，否则为 false
     */
    public boolean answer(ResourceScope scope, String task, String id, String answer, Instant now) {
        access.require(scope);
        return decisions.answer(scope, task, id, answer, now.toString()) == 1;
    }

    /**
     * Returns the scoped task's unanswered decision, or an empty map when none exists.
     * <p>返回作用域内任务的未回答决策，不存在时返回空映射。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param task task / 任务
     * @return the scoped task's unanswered decision, or an empty map when none exists / 作用域内任务的未回答决策，不存在时返回空映射
     */
    public Map<String, String> pendingDecision(ResourceScope scope, String task) {
        access.require(scope);
        Map<String, String> pending = decisions.pending(scope, task);
        return pending == null ? Map.of() : Map.copyOf(pending);
    }

    /**
     * Recovers interrupted.
     * <p>恢复已中断。
     *
     * @return recover interrupted as a numeric result / 恢复已中断的数值结果
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    public int recoverInterrupted() {
        String now = Instant.now().toString();
        var unfinished = tasks.selectList(new QueryWrapper<WebTaskEntity>().in("state", ACTIVE));
        var workspaces = new HashSet<>(tasks.interruptedApplicationWorkspaces());
        for (var task : unfinished) {
            var scope = new ResourceScope(task.workspaceId(), task.createdBy());
            int changed = tasks.transition(scope, task.id(), ACTIVE, "REVALIDATION_REQUIRED", null, "BACKEND_RESTARTED",
                    now, now);
            if (changed != 1)
                throw new IllegalStateException("Task changed during startup recovery");
            journal.append(scope, task.id(), "STATE", "REVALIDATION_REQUIRED", "{}", now);
            workspaces.add(task.workspaceId());
        }
        for (String workspace : workspaces)
            tasks.recoverApplications(workspace, now);
        return unfinished.size();
    }
}
