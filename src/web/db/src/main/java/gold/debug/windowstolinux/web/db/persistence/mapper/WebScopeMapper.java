package gold.debug.windowstolinux.web.db.persistence.mapper;

import gold.debug.windowstolinux.web.db.entity.ResourceScope;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WebScopeMapper {
    @Select("""
            SELECT count(*) FROM workspace_members m JOIN users u ON u.id=m.user_id
            JOIN workspaces w ON w.id=m.workspace_id
            WHERE m.workspace_id=#{scope.workspaceId} AND m.user_id=#{scope.userId}
            AND m.status='ACTIVE' AND u.status!='DISABLED' AND w.status!='DISABLED'
            """)
    int active(@Param("scope") ResourceScope scope);

    @Insert("INSERT OR IGNORE INTO users VALUES ('internal','Internal testing','INTERNAL',#{now},#{now})")
    int initializeUser(String now);
    @Insert("INSERT OR IGNORE INTO workspaces VALUES ('internal','Internal testing','internal','INTERNAL',#{now},#{now})")
    int initializeWorkspace(String now);
    @Insert("INSERT OR IGNORE INTO workspace_members VALUES ('internal','internal','ACTIVE',#{now})")
    int initializeMembership(String now);
}
