package gold.debug.windowstolinux.web.db;

import gold.debug.windowstolinux.web.db.entity.ResourceScope;
import gold.debug.windowstolinux.web.db.persistence.mapper.WebScopeMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

/** Initializes only the Phase 5 internal workspace, with no login credentials. */
@Component
public class WebPersistence {
    public static final ResourceScope INTERNAL_SCOPE = new ResourceScope("internal", "internal");
    private final WebScopeMapper mapper;
    public WebPersistence(WebScopeMapper mapper) { this.mapper = mapper; }

    @Transactional
    public void initializeInternalScope() {
        String now = Instant.now().toString();
        mapper.initializeUser(now); mapper.initializeWorkspace(now); mapper.initializeMembership(now);
    }
}
