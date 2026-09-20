package gold.debug.windowstolinux.web.db.persistence.mapper;

import gold.debug.windowstolinux.web.db.entity.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface WebTaskJournalMapper {
    @Insert("""
            INSERT INTO task_events(workspace_id,task_id,sequence,kind,message,detail_json,created_at)
            SELECT #{scope.workspaceId},#{id},coalesce(max(sequence),0)+1,#{kind},#{message},#{detail},#{now}
            FROM task_events WHERE workspace_id=#{scope.workspaceId} AND task_id=#{id}
            """)
    int append(@Param("scope") ResourceScope scope, @Param("id") String id, @Param("kind") String kind,
               @Param("message") String message, @Param("detail") String detail, @Param("now") String now);

    @Insert("""
            INSERT INTO task_steps(workspace_id,task_id,ordinal,name,state,recovery_json,updated_at)
            SELECT #{scope.workspaceId},#{id},coalesce(max(ordinal),-1)+1,#{message},'RECORDED',#{detail},#{now}
            FROM task_steps WHERE workspace_id=#{scope.workspaceId} AND task_id=#{id}
            """)
    int step(@Param("scope") ResourceScope scope, @Param("id") String id, @Param("message") String message,
             @Param("detail") String detail, @Param("now") String now);

    @Select("""
            SELECT sequence,kind,message,detail_json,created_at FROM task_events
            WHERE workspace_id=#{scope.workspaceId} AND task_id=#{id} AND sequence>#{after}
            ORDER BY sequence LIMIT 500
            """)
    List<StoredTaskEvent> events(@Param("scope") ResourceScope scope, @Param("id") String id, @Param("after") long after);
}
