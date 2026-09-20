package gold.debug.windowstolinux.web.db.persistence.mapper;

import gold.debug.windowstolinux.web.db.entity.ResourceScope;
import org.apache.ibatis.annotations.*;
import java.util.Map;

@Mapper
public interface WebConfigurationMapper {
    @Select("""
            SELECT count(*) FROM servers s JOIN task_targets x ON x.workspace_id=s.workspace_id AND x.server_id=s.id
            JOIN tasks t ON t.workspace_id=x.workspace_id AND t.id=x.task_id
            WHERE s.workspace_id=#{scope.workspaceId} AND s.id=#{id}
            AND t.state IN ('QUEUED','ANALYZING','WAITING_DECISION','RUNNING','CANCELLING')
            AND (s.host!=#{fields.host} OR s.port!=#{fields.port} OR s.username!=#{fields.username}
            OR s.secret_id IS NOT #{fields.secret_id} OR s.secret_version IS NOT #{fields.secret_version})
            """)
    int activeEndpointChange(@Param("scope") ResourceScope scope, @Param("id") String id, @Param("fields") Map<String,Object> fields);

    @Select("SELECT json_extract(#{document},'$.graph') WHERE json_extract(#{document},'$.deploymentState')='SUCCEEDED'")
    String successfulGraph(String document);

    @Insert("""
            INSERT INTO configuration_revisions(workspace_id,application_id,id,revision,created_by,document,digest,created_at)
            SELECT #{scope.workspaceId},#{id},#{id},#{revision},#{scope.userId},#{graph},#{digest},#{now}
            WHERE NOT EXISTS(SELECT 1 FROM configuration_revisions
            WHERE workspace_id=#{scope.workspaceId} AND application_id=#{id} AND digest=#{digest})
            """)
    int append(@Param("scope") ResourceScope scope, @Param("id") String id, @Param("revision") long revision,
               @Param("graph") String graph, @Param("digest") String digest, @Param("now") String now);
}
