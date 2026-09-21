package gold.debug.windowstolinux.shared.backup.manifest;

import java.util.Base64;

/**
 * Optional signature metadata kept separate from member integrity. / 与成员完整性分离的可选签名元数据。
 *
 * @param algorithm algorithm / 算法
 * @param keyId key id / 键标识
 * @param signature signature / 签名
 */
public record BackupProvenance(String algorithm, String keyId, String signature) {
    /**
     * Validates an unsigned marker or one Ed25519 signature. / 校验未签名标记或一个 Ed25519 签名。
     *
     * @param algorithm algorithm / 算法
     * @param keyId key id / 键标识
     * @param signature signature / 签名
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public BackupProvenance {
        algorithm = BackupManifestRules.requiredText(algorithm, "algorithm", 32);
        keyId = keyId == null ? "" : keyId.trim();
        signature = signature == null ? "" : signature.trim();
        if (algorithm.equals("none")) {
            if (!keyId.isEmpty() || !signature.isEmpty()) {
                throw new IllegalArgumentException("unsigned provenance cannot contain key material");
            }
        } else {
            if (!algorithm.equals("Ed25519")) throw new IllegalArgumentException("unsupported backup signature algorithm");
            keyId = BackupManifestRules.identifier(keyId, "keyId");
            try {
                if (Base64.getDecoder().decode(signature).length != 64) {
                    throw new IllegalArgumentException("Ed25519 signatures must contain 64 bytes");
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("signature must be canonical Base64 Ed25519 content", exception);
            }
        }
    }

    /**
     * Returns the explicit unsigned marker. / 返回显式未签名标记。
     *
     * @return the explicit unsigned marker / 显式未签名标记
     */
    public static BackupProvenance unsigned() {
        return new BackupProvenance("none", "", "");
    }

    /**
     * Reports whether provenance is present. / 报告是否存在来源签名。
     *
     * @return true when provenance is present, false otherwise / 报告是否存在来源签名时为 true，否则为 false
     */
    public boolean signed() {
        return !algorithm.equals("none");
    }
}
