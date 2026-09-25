package gold.debug.windowstolinux.web.db;

import java.time.Instant;

import gold.debug.windowstolinux.web.db.entity.ResourceScope;
import gold.debug.windowstolinux.web.db.persistence.mapper.WebScopeMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Initializes the internal Web workspace without creating login credentials.
 * <p>初始化内部 Web 工作区，不创建登录凭据。
 */
@Component
public class WebPersistence {
    /**
     * INTERNAL SCOPE.
     * <p>内部作用域。
     */
    public static final ResourceScope INTERNAL_SCOPE = new ResourceScope("internal", "internal");

    /**
     * Mapper.
     * <p>映射器。
     */
    private final WebScopeMapper mapper;
    /**
     * Binds the supplied dependencies and state for web persistence.
     * <p>为Web持久化绑定传入的依赖及状态。
     *
     * @param mapper mapper / 映射器
     */
    public WebPersistence(WebScopeMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Initializes internal scope.
     * <p>初始化内部作用域。
     */
    @Transactional
    public void initializeInternalScope() {
        String now = Instant.now().toString();
        mapper.initializeUser(now);
        mapper.initializeWorkspace(now);
        mapper.initializeMembership(now);
    }
}
