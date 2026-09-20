package gold.debug.windowstolinux.web.db.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import gold.debug.windowstolinux.web.db.entity.*;
import gold.debug.windowstolinux.web.db.persistence.mapper.WebSecretMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.NoSuchElementException;

/** Immutable encrypted revisions with complete workspace, ID, version and purpose keys. */
@Repository
@Transactional
public class WebSecretRepository {
    private final ScopeAccess access;
    private final WebSecretMapper mapper;
    public WebSecretRepository(ScopeAccess access, WebSecretMapper mapper) { this.access = access; this.mapper = mapper; }

    public int latestVersion(ResourceScope scope, String id, String purpose) {
        access.require(scope);
        var row = mapper.selectOne(new QueryWrapper<WebSecretEntity>().eq("workspace_id", scope.workspaceId())
                .eq("id", id).eq("purpose", purpose).orderByDesc("version").last("LIMIT 1"));
        return row == null ? 0 : row.version();
    }

    public void insert(ResourceScope scope, String id, int version, String purpose, byte[] ciphertext) {
        access.require(scope);
        if (mapper.insert(new WebSecretEntity(scope.workspaceId(), id, version, scope.userId(), purpose,
                ciphertext, Instant.now().toString())) != 1) throw new IllegalStateException("Credential revision was not saved");
    }

    public byte[] read(ResourceScope scope, String id, int version, String purpose) {
        access.require(scope);
        var row = mapper.selectOne(new QueryWrapper<WebSecretEntity>().eq("workspace_id", scope.workspaceId())
                .eq("id", id).eq("version", version).eq("purpose", purpose));
        if (row == null) throw new NoSuchElementException("Credential unavailable");
        return row.ciphertext();
    }
}
