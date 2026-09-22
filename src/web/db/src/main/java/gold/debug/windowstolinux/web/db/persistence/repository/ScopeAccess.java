package gold.debug.windowstolinux.web.db.persistence.repository;

import gold.debug.windowstolinux.web.db.entity.ResourceScope;
import gold.debug.windowstolinux.web.db.persistence.mapper.WebScopeMapper;
import org.springframework.stereotype.Component;

/**
 * Validates active workspace membership at the persistence boundary.
 * <p>在持久化边界校验活跃工作区成员关系。
 */
@Component
public final class ScopeAccess {
    /**
     * Mapper.
     * <p>映射器。
     */
    private final WebScopeMapper mapper;
    /**
     * Binds the supplied dependencies and state for scope access.
     * <p>为作用域访问绑定传入的依赖及状态。
     *
     * @param mapper mapper / 映射器
     */
    public ScopeAccess(WebScopeMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Requires scope access and rejects inputs outside the declared constraints.
     * <p>要求作用域访问并拒绝超出已声明约束的输入。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     */
    public void require(ResourceScope scope) {
        if (mapper.active(scope) != 1)
            throw new SecurityException("Resource scope is unavailable");
    }
}
