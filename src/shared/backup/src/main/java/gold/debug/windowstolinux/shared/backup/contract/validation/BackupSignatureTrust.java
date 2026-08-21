package gold.debug.windowstolinux.shared.backup.contract.validation;

import java.security.GeneralSecurityException;
import java.security.PublicKey;

/** Resolves an explicitly trusted backup-signing key. / 解析显式信任的备份签名密钥。 */
@FunctionalInterface
public interface BackupSignatureTrust {
    /** Returns the trusted Ed25519 key for one stable key identifier. / 返回一个稳定密钥标识对应的受信 Ed25519 密钥。 */
    PublicKey resolve(String keyId) throws GeneralSecurityException;
}
