package gold.debug.windowstolinux.shared.backup.manifest;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Objects;

/** Adds optional Ed25519 provenance without changing member integrity evidence. / 在不改变成员完整性证据的情况下添加可选 Ed25519 来源签名。 */
public final class BackupManifestSigner {
    private final BackupManifestCodec codec = new BackupManifestCodec();

    /** Signs the canonical unsigned manifest payload. / 对规范未签名清单载荷进行签名。 */
    public BackupManifest sign(BackupManifest manifest, String keyId, PrivateKey privateKey)
            throws GeneralSecurityException, IOException {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(privateKey, "privateKey");
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(codec.signaturePayload(manifest));
        String encoded = Base64.getEncoder().encodeToString(signer.sign());
        return manifest.withProvenance(new BackupProvenance("Ed25519", keyId, encoded));
    }
}
