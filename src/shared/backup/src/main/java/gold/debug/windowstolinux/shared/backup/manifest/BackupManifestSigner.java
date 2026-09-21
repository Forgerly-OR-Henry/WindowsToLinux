package gold.debug.windowstolinux.shared.backup.manifest;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Objects;

/**
 * Adds optional Ed25519 provenance without changing member integrity evidence. / 在不改变成员完整性证据的情况下添加可选 Ed25519 来源签名。
 */
public final class BackupManifestSigner {
    /**
     * Bound backup manifest codec collaborator for codec.
     * <p>处理编解码器的备份清单编解码器协作对象。
     */
    private final BackupManifestCodec codec = new BackupManifestCodec();

    /**
     * Signs the canonical unsigned manifest payload. / 对规范未签名清单载荷进行签名。
     *
     * @param manifest validated ownership or backup inventory document / 已验证归属或备份资源清单文档
     * @param keyId key id / 键标识
     * @param privateKey private key / 私有键
     * @return constructed or resolved backup manifest / 构造或解析得到的备份清单
     * @throws GeneralSecurityException if the requested cryptographic primitive or key cannot be used / 无法使用请求的加密原语或密钥时
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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
