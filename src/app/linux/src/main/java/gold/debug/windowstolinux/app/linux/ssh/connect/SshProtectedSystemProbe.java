package gold.debug.windowstolinux.app.linux.ssh.connect;

import java.io.IOException;
import java.util.List;

import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClient.Attribute;
import org.apache.sshd.sftp.client.SftpClient.Attributes;
import org.apache.sshd.sftp.common.SftpConstants;

/** Reads fixed launch-path metadata from the authenticated SSH subsystem. / 从已认证 SSH 子系统读取固定启动路径的元数据。 */
final class SshProtectedSystemProbe {
    /** Fixed non-link executables used at SSH framing and before ordinary probe admission. / 用于 SSH 帧启动和常规探测准入前的固定非链接程序。 */
    private static final List<String> SYSTEM_TOOLS = List.of("/usr/bin/env", "/usr/bin/stat", "/usr/bin/readlink");

    /** Keeps verification stateless between command requests. / 保持命令请求之间的校验无状态。 */
    private SshProtectedSystemProbe() {
    }

    /** Checks each ancestor before its children and recognizes the system merged-usr /bin link. / 先核验祖先再核验子项，并识别系统 merged-usr 的 /bin 链接。
     * @param sftp metadata channel on the original authenticated session / 原已认证会话上的元数据通道
     * @param privilegeChecked whether to require the additional V2 launch tools and reject set-ID / 是否校验 V2 附加启动工具并拒绝 set-ID
     * @return whether every required fixed path met its metadata constraints / 每个必需固定路径是否满足元数据约束
     * @throws IOException if a metadata request fails / 元数据请求失败时 */
    static boolean verify(SftpClient sftp, boolean privilegeChecked) throws IOException {
        if (sftp.getVersion() != SftpConstants.SFTP_V3) return false;
        for (String directory : List.of("/", "/usr", "/usr/bin")) {
            if (!protectedDirectory(sftp.lstat(directory))) return false;
        }
        for (String executable : SYSTEM_TOOLS) {
            if (!protectedExecutable(sftp.lstat(executable), privilegeChecked)) return false;
        }
        if (!protectedBash(sftp, privilegeChecked)) return false;
        if (!privilegeChecked) return true;
        if (!protectedExecutable(sftp.lstat("/usr/bin/setpriv"), true)) return false;
        var sbin = sftp.lstat("/usr/sbin");
        if (!sbin.isSymbolicLink())
            return protectedDirectory(sbin) && protectedExecutable(sftp.lstat("/usr/sbin/getcap"), true);
        if (!rootOwned(sbin)) return false;
        String target = sftp.readLink("/usr/sbin");
        return ("bin".equals(target) || "/usr/bin".equals(target))
            && protectedExecutable(sftp.lstat("/usr/bin/getcap"), true);
    }

    private static boolean protectedBash(SftpClient sftp, boolean privilegeChecked) throws IOException {
        var bin = sftp.lstat("/bin");
        if (!bin.isSymbolicLink())
            return protectedDirectory(bin) && protectedExecutable(sftp.lstat("/bin/bash"), privilegeChecked);
        if (!rootOwned(bin)) return false;
        String target = sftp.readLink("/bin");
        return ("usr/bin".equals(target) || "/usr/bin".equals(target))
            && protectedExecutable(sftp.lstat("/usr/bin/bash"), privilegeChecked);
    }

    /** Recognizes a protected directory without following an alias. / 识别受保护目录，避免跟随别名。
     * @param attributes metadata for the directory entry itself / 目录项本身的元数据
     * @return whether a protected directory was observed / 是否观测到受保护目录 */
    private static boolean protectedDirectory(Attributes attributes) {
        return protectedMode(attributes) && attributes.isDirectory();
    }

    /** Requires a protected regular file with the owner execute bit set. / 要求受保护的普通文件设置所有者执行位。
     * @param attributes metadata for the executable entry itself / 可执行文件项本身的元数据
     * @param privilegeChecked whether to reject set-ID bits / 是否拒绝 set-ID 位
     * @return whether a protected executable was observed / 是否观测到受保护可执行文件 */
    private static boolean protectedExecutable(Attributes attributes, boolean privilegeChecked) {
        return protectedMode(attributes) && attributes.isRegularFile()
            && (attributes.getPermissions() & 0100) != 0
            && (!privilegeChecked || (attributes.getPermissions() & 06000) == 0);
    }

    /** Rejects modes that grant group or other write access. / 拒绝授予组或其他用户写权限的模式。
     * @param attributes observed numeric owner and mode metadata / 已观测的数字所有者与模式元数据
     * @return whether the reported owner and mode satisfy the gate / 报告的所有者和模式是否满足门禁 */
    private static boolean protectedMode(Attributes attributes) {
        return rootOwned(attributes) && (attributes.getPermissions() & 0022) == 0;
    }

    /** Requires explicit numeric root ownership and the type-bearing mode attribute. / 要求显式数字 root 所有权及带文件类型的模式属性。
     * @param attributes attributes returned by SFTP LSTAT / SFTP LSTAT 返回的属性
     * @return whether UID zero and mode are both present / 是否明确返回 UID 零及模式 */
    private static boolean rootOwned(Attributes attributes) {
        return attributes.getFlags().contains(Attribute.UidGid) && attributes.getFlags().contains(Attribute.Perms)
            && attributes.getUserId() == 0;
    }
}
