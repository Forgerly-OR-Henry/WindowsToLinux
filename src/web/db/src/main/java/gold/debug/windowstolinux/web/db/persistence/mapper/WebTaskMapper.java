package gold.debug.windowstolinux.web.db.persistence.mapper;

import java.util.Set;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import gold.debug.windowstolinux.web.db.entity.*;
import org.apache.ibatis.annotations.*;

/**
 * Maps scoped task records to explicit database statements.
 * <p>将限定作用域的任务记录映射到显式数据库语句。
 */
@Mapper
public interface WebTaskMapper extends BaseMapper<WebTaskEntity> {
    /**
     * Updates a workspace task only from one of the permitted source states and records completion metadata.
     * <p>仅从允许的源状态更新工作区任务，并记录完成元数据。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param from from / 来源
     * @param to the supplied string / 所提供的字符串
     * @param result typed outcome produced by the delegated operation / 被委派操作产生的类型化结果
     * @param error error / 错误
     * @param now current instant supplied by the caller or clock / 调用方或时钟提供的当前时刻
     * @param finished finished / 已完成
     * @return number of rows updated, zero when the expected source state did not match / 更新行数；预期源状态不匹配时为零
     */
    int transition(@Param("scope") ResourceScope scope, @Param("id") String id, @Param("from") Set<String> from,
            @Param("to") String to, @Param("result") String result, @Param("error") String error,
            @Param("now") String now, @Param("finished") String finished);

    /**
     * Recovers applications.
     * <p>恢复应用集合。
     *
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param now current instant supplied by the caller or clock / 调用方或时钟提供的当前时刻
     * @return recover applications as a numeric result / 恢复应用集合的数值结果
     */
    @Update("""
            UPDATE applications SET document=json_set(document,'$.deploymentState','REVALIDATION_REQUIRED'),
            version=version+1,updated_at=#{now}
            WHERE workspace_id=#{workspace} AND json_extract(document,'$.deploymentState')='RUNNING'
            """)
    int recoverApplications(@Param("workspace") String workspace, @Param("now") String now);

    /**
     * Returns interrupted application workspaces.
     * <p>返回已中断应用工作区集合。
     *
     * @return interrupted application workspaces / 已中断应用工作区集合
     */
    @Select("SELECT DISTINCT workspace_id FROM applications WHERE json_extract(document,'$.deploymentState')='RUNNING'")
    java.util.List<String> interruptedApplicationWorkspaces();
}
