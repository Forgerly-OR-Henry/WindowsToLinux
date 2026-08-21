package gold.debug.windowstolinux.app.secret.crypto;

import gold.debug.windowstolinux.shared.config.secretref.ResolvedSecretRevision;

import java.util.List;
import java.util.Objects;

/** Owns a complete decoded backup-secret revision set and clears it as one unit. / 持有完整已解码备份秘密修订集并将其整体清零。 */
public final class BackupSecretDocument implements AutoCloseable {
    private final List<ResolvedSecretRevision> revisions;

    /** Takes ownership of one complete non-empty revision set. / 接管一个完整且非空的修订集。 */
    BackupSecretDocument(List<ResolvedSecretRevision> revisions) {
        this.revisions = List.copyOf(Objects.requireNonNull(revisions, "revisions"));
        if (this.revisions.isEmpty()) {
            throw new IllegalArgumentException("backup secret document requires revisions");
        }
    }

    /** Returns owned short-lived revisions; callers must not retain them after close. / 返回持有的短生命周期修订；关闭后调用方不得继续持有。 */
    public List<ResolvedSecretRevision> revisions() {
        return revisions;
    }

    /** Clears every decoded secret revision. / 清零每个已解码秘密修订。 */
    @Override
    public void close() {
        revisions.forEach(ResolvedSecretRevision::close);
    }
}
