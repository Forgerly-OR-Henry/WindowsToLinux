package gold.debug.windowstolinux.web.db.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import gold.debug.windowstolinux.web.db.entity.*;
import org.apache.ibatis.annotations.*;
import java.util.Map;

@Mapper
public interface WebTaskDecisionMapper extends BaseMapper<WebTaskDecisionEntity> {
    @Update("""
            UPDATE task_decisions SET answer_json=#{answer},answered_by=#{scope.userId},answered_at=#{now}
            WHERE workspace_id=#{scope.workspaceId} AND task_id=#{task} AND id=#{id}
            AND answer_json IS NULL AND expires_at>#{now}
            AND EXISTS(SELECT 1 FROM tasks WHERE tasks.workspace_id=task_decisions.workspace_id
            AND tasks.id=task_decisions.task_id AND tasks.state='WAITING_DECISION')
            """)
    int answer(@Param("scope") ResourceScope scope, @Param("task") String task, @Param("id") String id,
               @Param("answer") String answer, @Param("now") String now);

    @Select("""
            SELECT d.id,d.kind,d.prompt_json AS prompt,d.expires_at AS expiresAt FROM task_decisions d JOIN tasks t
            ON t.workspace_id=d.workspace_id AND t.id=d.task_id
            WHERE d.workspace_id=#{scope.workspaceId} AND d.task_id=#{task}
            AND d.answer_json IS NULL AND t.state='WAITING_DECISION' ORDER BY d.rowid DESC LIMIT 1
            """)
    Map<String,String> pending(@Param("scope") ResourceScope scope, @Param("task") String task);
}
