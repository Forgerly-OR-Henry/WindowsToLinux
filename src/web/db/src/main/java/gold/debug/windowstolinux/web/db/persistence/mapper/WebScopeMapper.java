package gold.debug.windowstolinux.web.db.persistence.mapper;

import gold.debug.windowstolinux.web.db.entity.ResourceScope;
import org.apache.ibatis.annotations.*;

/**
 * Maps scoped scope records to explicit database statements.
 * <p>将限定作用域的作用域记录映射到显式数据库语句。
 */
@Mapper
public interface WebScopeMapper {
    /**
     * Counts matching active memberships whose user and workspace are not disabled.
     * <p>统计用户及工作区均未禁用的匹配活跃成员关系。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @return active as a numeric result / 活跃的数值结果
     */
    @Select("""
            SELECT count(*) FROM workspace_members m JOIN users u ON u.id=m.user_id
            JOIN workspaces w ON w.id=m.workspace_id
            WHERE m.workspace_id=#{scope.workspaceId} AND m.user_id=#{scope.userId}
            AND m.status='ACTIVE' AND u.status!='DISABLED' AND w.status!='DISABLED'
            """)
    int active(@Param("scope") ResourceScope scope);

    /**
     * Initializes user.
     * <p>初始化用户。
     *
     * @param now current instant supplied by the caller or clock / 调用方或时钟提供的当前时刻
     * @return initialize user as a numeric result / 初始化用户的数值结果
     */
    @Insert("INSERT OR IGNORE INTO users VALUES ('internal','Internal testing','INTERNAL',#{now},#{now})")
    int initializeUser(String now);
    /**
     * Initializes platform-owned work area with enforced path boundaries.
     * <p>初始化具有路径边界约束的平台工作区。
     *
     * @param now current instant supplied by the caller or clock / 调用方或时钟提供的当前时刻
     * @return initialize workspace as a numeric result / 初始化工作区的数值结果
     */
    @Insert("INSERT OR IGNORE INTO workspaces VALUES ('internal','Internal testing','internal','INTERNAL',#{now},#{now})")
    int initializeWorkspace(String now);
    /**
     * Initializes membership.
     * <p>初始化成员关系。
     *
     * @param now current instant supplied by the caller or clock / 调用方或时钟提供的当前时刻
     * @return initialize membership as a numeric result / 初始化成员关系的数值结果
     */
    @Insert("INSERT OR IGNORE INTO workspace_members VALUES ('internal','internal','ACTIVE',#{now})")
    int initializeMembership(String now);
}
