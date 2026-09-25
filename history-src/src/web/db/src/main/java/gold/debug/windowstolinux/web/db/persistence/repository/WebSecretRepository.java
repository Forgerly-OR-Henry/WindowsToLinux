package gold.debug.windowstolinux.web.db.persistence.repository;

import java.time.Instant;
import java.util.NoSuchElementException;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import gold.debug.windowstolinux.web.db.entity.*;
import gold.debug.windowstolinux.web.db.persistence.mapper.WebSecretMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores immutable encrypted revisions keyed by workspace, identifier, version and purpose.
 * <p>存储由工作区、标识、版本及用途联合定位的不可变加密修订。
 */
@Repository
@Transactional
public class WebSecretRepository {
    /**
     * Access mode.
     * <p>访问模式。
     */
    private final ScopeAccess access;

    /**
     * Mapper.
     * <p>映射器。
     */
    private final WebSecretMapper mapper;
    /**
     * Binds the supplied dependencies and state for web secret repository.
     * <p>为Web秘密仓库绑定传入的依赖及状态。
     *
     * @param access access mode / 访问模式
     * @param mapper mapper / 映射器
     */
    public WebSecretRepository(ScopeAccess access, WebSecretMapper mapper) {
        this.access = access;
        this.mapper = mapper;
    }

    /**
     * Finds the latest secret revision within the authorized workspace and purpose, returning zero when absent.
     * <p>查找已授权工作区及用途内的最新秘密修订，不存在时返回零。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param purpose purpose / 用途
     * @return latest stored revision, or zero if absent / 最新持久化修订；不存在时为零
     */
    public int latestVersion(ResourceScope scope, String id, String purpose) {
        access.require(scope);
        var row = mapper.selectOne(new QueryWrapper<WebSecretEntity>().eq("workspace_id", scope.workspaceId())
                .eq("id", id).eq("purpose", purpose).orderByDesc("version").last("LIMIT 1"));
        return row == null ? 0 : row.version();
    }

    /**
     * Inserts web secret.
     * <p>插入Web秘密。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param purpose purpose / 用途
     * @param ciphertext ciphertext / 密文
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    public void insert(ResourceScope scope, String id, int version, String purpose, byte[] ciphertext) {
        access.require(scope);
        if (mapper.insert(new WebSecretEntity(scope.workspaceId(), id, version, scope.userId(), purpose, ciphertext,
                Instant.now().toString())) != 1)
            throw new IllegalStateException("Credential revision was not saved");
    }

    /**
     * Reads web secret.
     * <p>读取Web秘密。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param purpose purpose / 用途
     * @return web secret / Web秘密
     */
    public byte[] read(ResourceScope scope, String id, int version, String purpose) {
        access.require(scope);
        var row = mapper.selectOne(new QueryWrapper<WebSecretEntity>().eq("workspace_id", scope.workspaceId())
                .eq("id", id).eq("version", version).eq("purpose", purpose));
        if (row == null)
            throw new NoSuchElementException("Credential unavailable");
        return row.ciphertext();
    }
}
