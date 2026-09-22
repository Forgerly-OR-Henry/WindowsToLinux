package gold.debug.windowstolinux.web.db.persistence.mapper;

import java.util.List;

import gold.debug.windowstolinux.web.db.entity.*;
import org.apache.ibatis.annotations.*;

/**
 * Maps scoped task journal records to explicit database statements.
 * <p>将限定作用域的任务日志记录映射到显式数据库语句。
 */
@Mapper
public interface WebTaskJournalMapper {
    /**
     * Appends web task journal mapper.
     * <p>追加Web任务日志映射器。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
     * @param message localized explanation / 本地化说明
     * @param detail detail / 详情
     * @param now current instant supplied by the caller or clock / 调用方或时钟提供的当前时刻
     * @return append as a numeric result / 追加的数值结果
     */
    @Insert("""
            INSERT INTO task_events(workspace_id,task_id,sequence,kind,message,detail_json,created_at)
            SELECT #{scope.workspaceId},#{id},coalesce(max(sequence),0)+1,#{kind},#{message},#{detail},#{now}
            FROM task_events WHERE workspace_id=#{scope.workspaceId} AND task_id=#{id}
            """)
    int append(@Param("scope") ResourceScope scope, @Param("id") String id, @Param("kind") String kind,
            @Param("message") String message, @Param("detail") String detail, @Param("now") String now);

    /**
     * Appends a task step with the next ordinal and the supplied safe detail document.
     * <p>使用下一顺序号及所提供安全详情文档追加任务步骤。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param message localized explanation / 本地化说明
     * @param detail detail / 详情
     * @param now current instant supplied by the caller or clock / 调用方或时钟提供的当前时刻
     * @return step as a numeric result / 步骤的数值结果
     */
    @Insert("""
            INSERT INTO task_steps(workspace_id,task_id,ordinal,name,state,recovery_json,updated_at)
            SELECT #{scope.workspaceId},#{id},coalesce(max(ordinal),-1)+1,#{message},'RECORDED',#{detail},#{now}
            FROM task_steps WHERE workspace_id=#{scope.workspaceId} AND task_id=#{id}
            """)
    int step(@Param("scope") ResourceScope scope, @Param("id") String id, @Param("message") String message,
            @Param("detail") String detail, @Param("now") String now);

    /**
     * Reads recorded events in the order required by the caller's cursor.
     * <p>按调用方游标要求的顺序读取已记录事件。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param after after / 之后
     * @return recorded events in the order required by the caller's cursor / 按调用方游标要求的顺序读取已记录事件
     */
    @Select("""
            SELECT sequence,kind,message,detail_json,created_at FROM task_events
            WHERE workspace_id=#{scope.workspaceId} AND task_id=#{id} AND sequence>#{after}
            ORDER BY sequence LIMIT 500
            """)
    List<StoredTaskEvent> events(@Param("scope") ResourceScope scope, @Param("id") String id,
            @Param("after") long after);
}
