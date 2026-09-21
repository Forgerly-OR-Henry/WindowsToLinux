package gold.debug.windowstolinux.web.db.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import gold.debug.windowstolinux.web.db.entity.WebPreferenceEntity;
import org.apache.ibatis.annotations.*;

/**
 * Maps scoped preference records to explicit database statements.
 * <p>将限定作用域的偏好记录映射到显式数据库语句。
 */
@Mapper
public interface WebPreferenceMapper extends BaseMapper<WebPreferenceEntity> {
    /**
     * Persists web preference mapper.
     * <p>持久化Web偏好映射器。
     *
     * @param preference preference / 偏好
     * @return save as a numeric result / 保存的数值结果
     */
    @Insert("""
            INSERT INTO preferences(workspace_id,user_id,name,value,updated_at)
            VALUES (#{workspaceId},#{userId},#{name},#{value},#{updatedAt})
            ON CONFLICT(workspace_id,user_id,name) DO UPDATE SET value=excluded.value,updated_at=excluded.updated_at
            """)
    int save(WebPreferenceEntity preference);
}
