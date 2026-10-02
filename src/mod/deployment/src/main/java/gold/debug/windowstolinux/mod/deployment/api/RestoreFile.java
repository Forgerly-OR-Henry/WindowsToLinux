package gold.debug.windowstolinux.mod.deployment.api;

import java.nio.file.Path;

/** Backup-verified local file for target preparation. / 备份已验证并用于目标准备的本地文件。
 * @param bindingId original binding / 原绑定
 * @param verifiedLocalFile verified local path / 已验证本地路径
 * @param byteCount verified length / 已验证长度
 * @param sha256 verified digest / 已验证摘要 */
public record RestoreFile(String bindingId, Path verifiedLocalFile, long byteCount, String sha256) {
}
