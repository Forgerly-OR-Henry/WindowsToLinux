package gold.debug.windowstolinux.app.windows.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Verifies a staged package against one pinned Ed25519 release trust root. / 使用单个固定 Ed25519 发布信任根验证已暂存软件包。
 */
public final class DesktopUpdateVerifier {
    /**
     * BUFFER SIZE.
     * <p>缓冲区大小。
     */
    private static final int BUFFER_SIZE = 64 * 1024;

    /**
     * Returns evidence only after every trust and compatibility gate passes. / 仅在全部信任及兼容门通过后返回证据。
     *
     * @param packageFile package file / 软件包文件
     * @param manifest validated ownership or backup inventory document / 已验证归属或备份资源清单文档
     * @param policy explicit validation and resource-bound policy / 显式校验及资源边界策略
     * @return evidence only after every trust and compatibility gate passes / 仅在全部信任及兼容门通过后返回证据
     * @throws DesktopUpdateException if the desktop update boundary rejects the operation / Desktop更新边界拒绝当前操作时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DesktopUpdateVerification verify(
            Path packageFile, DesktopUpdateManifest manifest, DesktopUpdateTrustPolicy policy)
            throws DesktopUpdateException {
        Path normalized = Objects.requireNonNull(packageFile, "packageFile").toAbsolutePath().normalize();
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(policy, "policy");
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            throw DesktopUpdateException.create(DesktopUpdateFailureType.PACKAGE_INVALID,
                    "staged update package is not a regular non-link file");
        }
        if (!manifest.keyId().equals(policy.trustedKeyId())) {
            throw DesktopUpdateException.create(DesktopUpdateFailureType.SIGNATURE_INVALID,
                    "update metadata key identifier differs from the pinned trust root");
        }
        if (policy.revokedReleaseIds().contains(manifest.releaseId())) {
            throw DesktopUpdateException.create(DesktopUpdateFailureType.VERSION_REJECTED,
                    "the signed desktop release has been revoked by local release policy");
        }
        if (manifest.issuedAt().isAfter(policy.verificationTime())
                || !manifest.expiresAt().isAfter(policy.verificationTime())) {
            throw DesktopUpdateException.create(DesktopUpdateFailureType.MANIFEST_INVALID,
                    "signed update metadata is not currently valid");
        }
        if (manifest.architecture() != policy.currentArchitecture()) {
            throw DesktopUpdateException.create(DesktopUpdateFailureType.ARCHITECTURE_REJECTED,
                    "desktop update package architecture differs from the running application");
        }
        int comparison = manifest.version().compareTo(policy.currentVersion());
        boolean approvedEmergencyRollback = comparison < 0 && manifest.emergencyRollback()
                && policy.emergencyRollbackApproved();
        if (comparison == 0 || comparison < 0 && !approvedEmergencyRollback) {
            throw DesktopUpdateException.create(DesktopUpdateFailureType.VERSION_REJECTED,
                    "desktop update is equal to or older than the running version without approved signed rollback");
        }
        PackageIdentity identity = packageIdentity(normalized);
        if (identity.bytes() != manifest.packageBytes() || !identity.sha256().equals(manifest.packageSha256())) {
            throw DesktopUpdateException.create(DesktopUpdateFailureType.PACKAGE_INVALID,
                    "staged package size or SHA-256 differs from signed metadata");
        }
        verifySignature(manifest, policy);
        return new DesktopUpdateVerification(normalized, manifest.releaseId(), manifest.version(),
                manifest.architecture(), identity.bytes(), identity.sha256(), policy.verificationTime(), List.of(
                "staged package size and SHA-256 match signed metadata",
                "Ed25519 signature matches the pinned release trust root",
                approvedEmergencyRollback
                        ? "signed emergency rollback was explicitly approved"
                        : "release version is newer than the running application",
                "package architecture matches the running application"));
    }

    /**
     * Builds package identity from the supplied package identity inputs.
     * <p>根据所提供软件包身份输入构建软件包身份。
     *
     * @param packageFile package file / 软件包文件
     * @return package identity from the supplied package identity inputs / 根据所提供软件包身份输入构建软件包身份
     * @throws DesktopUpdateException if the desktop update boundary rejects the operation / Desktop更新边界拒绝当前操作时
     */
    private static PackageIdentity packageIdentity(Path packageFile) throws DesktopUpdateException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long count = 0;
            byte[] buffer = new byte[BUFFER_SIZE];
            try (InputStream input = Files.newInputStream(packageFile)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    count = Math.addExact(count, read);
                    digest.update(buffer, 0, read);
                }
            }
            return new PackageIdentity(count, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | GeneralSecurityException | ArithmeticException exception) {
            throw DesktopUpdateException.create(DesktopUpdateFailureType.PACKAGE_INVALID,
                    "staged update package could not be hashed safely", exception);
        }
    }

    /**
     * Verifies signature.
     * <p>验证签名。
     *
     * @param manifest validated ownership or backup inventory document / 已验证归属或备份资源清单文档
     * @param policy explicit validation and resource-bound policy / 显式校验及资源边界策略
     * @throws DesktopUpdateException if the desktop update boundary rejects the operation / Desktop更新边界拒绝当前操作时
     */
    private static void verifySignature(
            DesktopUpdateManifest manifest, DesktopUpdateTrustPolicy policy) throws DesktopUpdateException {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(policy.trustedPublicKey());
            verifier.update(manifest.signedPayload());
            if (!verifier.verify(Base64.getDecoder().decode(manifest.signatureBase64()))) {
                throw DesktopUpdateException.create(DesktopUpdateFailureType.SIGNATURE_INVALID,
                        "desktop update metadata signature is invalid");
            }
        } catch (DesktopUpdateException exception) {
            throw exception;
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw DesktopUpdateException.create(DesktopUpdateFailureType.SIGNATURE_INVALID,
                    "desktop update signature could not be verified", exception);
        }
    }

    /**
     * Carries verified update package identity and format information.
     * <p>携带已验证更新包的身份及格式信息。
     *
     * @param bytes content buffer processed by the current codec or stream / 当前编解码器或流处理的内容缓冲区
     * @param sha256 lower-case hexadecimal SHA-256 digest / 小写十六进制 SHA-256 摘要
     */
    private record PackageIdentity(long bytes, String sha256) { }
}
