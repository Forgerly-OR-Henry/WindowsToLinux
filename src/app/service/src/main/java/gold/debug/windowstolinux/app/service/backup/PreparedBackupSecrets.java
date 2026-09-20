package gold.debug.windowstolinux.app.service.backup;

import gold.debug.windowstolinux.shared.backup.crypto.BackupSecretDocument;

import java.util.Objects;

/** Local candidate plus its complete authenticated short-lived secret revisions. / 本地候选及其完整已认证短生命周期秘密修订。 */
public record PreparedBackupSecrets(
        PreparedBackupCandidate candidate,
        BackupSecretDocument secrets
) implements AutoCloseable {
    /** Binds one candidate to one owned decoded secret document. / 将一个候选绑定到一个持有的已解码秘密文档。 */
    public PreparedBackupSecrets {
        candidate = Objects.requireNonNull(candidate, "candidate");
        secrets = Objects.requireNonNull(secrets, "secrets");
    }

    /** Clears all decoded secret revisions. / 清零全部已解码秘密修订。 */
    @Override
    public void close() {
        secrets.close();
    }
}
