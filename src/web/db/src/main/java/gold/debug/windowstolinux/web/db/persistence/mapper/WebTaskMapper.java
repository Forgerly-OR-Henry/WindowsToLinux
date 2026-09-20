package gold.debug.windowstolinux.web.db.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import gold.debug.windowstolinux.web.db.entity.*;
import org.apache.ibatis.annotations.*;
import java.util.Set;

@Mapper
public interface WebTaskMapper extends BaseMapper<WebTaskEntity> {
    int transition(@Param("scope") ResourceScope scope, @Param("id") String id,
                   @Param("from") Set<String> from, @Param("to") String to, @Param("result") String result,
                   @Param("error") String error, @Param("now") String now, @Param("finished") String finished);

    @Update("""
            UPDATE applications SET document=json_set(document,'$.deploymentState','REVALIDATION_REQUIRED'),
            version=version+1,updated_at=#{now}
            WHERE workspace_id=#{workspace} AND json_extract(document,'$.deploymentState')='RUNNING'
            """)
    int recoverApplications(@Param("workspace") String workspace, @Param("now") String now);

    @Select("SELECT DISTINCT workspace_id FROM applications WHERE json_extract(document,'$.deploymentState')='RUNNING'")
    java.util.List<String> interruptedApplicationWorkspaces();
}
