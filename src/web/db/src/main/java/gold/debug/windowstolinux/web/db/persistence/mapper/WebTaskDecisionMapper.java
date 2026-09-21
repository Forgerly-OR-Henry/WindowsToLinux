package gold.debug.windowstolinux.web.db.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import gold.debug.windowstolinux.web.db.entity.*;
import org.apache.ibatis.annotations.*;
import java.util.Map;

/**
 * Maps scoped task decision records to explicit database statements.
 * <p>将限定作用域的任务决定记录映射到显式数据库语句。
 */
@Mapper
public interface WebTaskDecisionMapper extends BaseMapper<WebTaskDecisionEntity> {
    /**
     * Records the answer for web task decision mapper.
     * <p>记录以下交互的回答：Web任务决定映射器。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param task task / 任务
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param answer answer / 回答
     * @param now current instant supplied by the caller or clock / 调用方或时钟提供的当前时刻
     * @return answer as a numeric result / 回答的数值结果
     */
    @Update("""
            UPDATE task_decisions SET answer_json=#{answer},answered_by=#{scope.userId},answered_at=#{now}
            WHERE workspace_id=#{scope.workspaceId} AND task_id=#{task} AND id=#{id}
            AND answer_json IS NULL AND expires_at>#{now}
            AND EXISTS(SELECT 1 FROM tasks WHERE tasks.workspace_id=task_decisions.workspace_id
            AND tasks.id=task_decisions.task_id AND tasks.state='WAITING_DECISION')
            """)
    int answer(@Param("scope") ResourceScope scope, @Param("task") String task, @Param("id") String id,
               @Param("answer") String answer, @Param("now") String now);

    /**
     * Selects the latest unanswered decision for a task currently waiting for a decision in the workspace.
     * <p>在工作区内选择当前等待决策任务的最新未回答决策。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param task task / 任务
     * @return the latest unanswered decision for a task currently waiting for a decision in the workspace / 在工作区内选择当前等待决策任务的最新未回答决策
     */
    @Select("""
            SELECT d.id,d.kind,d.prompt_json AS prompt,d.expires_at AS expiresAt FROM task_decisions d JOIN tasks t
            ON t.workspace_id=d.workspace_id AND t.id=d.task_id
            WHERE d.workspace_id=#{scope.workspaceId} AND d.task_id=#{task}
            AND d.answer_json IS NULL AND t.state='WAITING_DECISION' ORDER BY d.rowid DESC LIMIT 1
            """)
    Map<String,String> pending(@Param("scope") ResourceScope scope, @Param("task") String task);
}
