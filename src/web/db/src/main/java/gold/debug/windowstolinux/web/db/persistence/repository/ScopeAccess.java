package gold.debug.windowstolinux.web.db.persistence.repository;

import gold.debug.windowstolinux.web.db.entity.ResourceScope;
import gold.debug.windowstolinux.web.db.persistence.mapper.WebScopeMapper;
import org.springframework.stereotype.Component;

/** Active membership validation; authentication remains a Phase 6 responsibility. */
@Component
public final class ScopeAccess {
    private final WebScopeMapper mapper;
    public ScopeAccess(WebScopeMapper mapper) { this.mapper = mapper; }
    public void require(ResourceScope scope) {
        if (mapper.active(scope) != 1) throw new SecurityException("Resource scope is unavailable");
    }
}
