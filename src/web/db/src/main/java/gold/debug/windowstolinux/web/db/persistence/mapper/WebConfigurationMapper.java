package gold.debug.windowstolinux.web.db.persistence.mapper;

import gold.debug.windowstolinux.web.db.entity.ResourceScope;
import org.apache.ibatis.annotations.*;
import java.util.Map;

/**
 * Maps scoped configuration records to explicit database statements.
 * <p>将限定作用域的配置记录映射到显式数据库语句。
 */
@Mapper
public interface WebConfigurationMapper {
    /**
     * Counts active tasks that would be affected by changing the selected server's connection endpoint.
     * <p>统计更改所选服务器连接端点会影响的活跃任务数。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param fields allowed or requested input field definitions / 允许或请求的输入字段定义
     * @return active endpoint change as a numeric result / 活跃端点变更的数值结果
     */
    @Select("""
            SELECT count(*) FROM servers s JOIN task_targets x ON x.workspace_id=s.workspace_id AND x.server_id=s.id
            JOIN tasks t ON t.workspace_id=x.workspace_id AND t.id=x.task_id
            WHERE s.workspace_id=#{scope.workspaceId} AND s.id=#{id}
            AND t.state IN ('QUEUED','ANALYZING','WAITING_DECISION','RUNNING','CANCELLING')
            AND (s.host!=#{fields.host} OR s.port!=#{fields.port} OR s.username!=#{fields.username}
            OR s.secret_id IS NOT #{fields.secret_id} OR s.secret_version IS NOT #{fields.secret_version})
            """)
    int activeEndpointChange(@Param("scope") ResourceScope scope, @Param("id") String id, @Param("fields") Map<String,Object> fields);

    /**
     * Extracts the stored graph only when the document records a successful deployment.
     * <p>仅在文档记录成功部署时提取持久化应用图。
     *
     * @param document document / 文档
     * @return the stored graph only when the document records a successful deployment / 仅在文档记录成功部署时提取持久化应用图
     */
    @Select("SELECT json_extract(#{document},'$.graph') WHERE json_extract(#{document},'$.deploymentState')='SUCCEEDED'")
    String successfulGraph(String document);

    /**
     * Appends web configuration mapper.
     * <p>追加Web配置映射器。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param revision immutable configuration or secret revision number / 不可变配置或秘密修订号
     * @param graph graph / 图
     * @param digest content identity used for independent verification / 独立验证所用的内容身份
     * @param now current instant supplied by the caller or clock / 调用方或时钟提供的当前时刻
     * @return append as a numeric result / 追加的数值结果
     */
    @Insert("""
            INSERT INTO configuration_revisions(workspace_id,application_id,id,revision,created_by,document,digest,created_at)
            SELECT #{scope.workspaceId},#{id},#{id},#{revision},#{scope.userId},#{graph},#{digest},#{now}
            WHERE NOT EXISTS(SELECT 1 FROM configuration_revisions
            WHERE workspace_id=#{scope.workspaceId} AND application_id=#{id} AND digest=#{digest})
            """)
    int append(@Param("scope") ResourceScope scope, @Param("id") String id, @Param("revision") long revision,
               @Param("graph") String graph, @Param("digest") String digest, @Param("now") String now);
}
