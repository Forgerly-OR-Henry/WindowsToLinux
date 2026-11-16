package gold.debug.windowstolinux.app.linux.ssh.connect;

import java.io.IOException;
import java.util.List;

import gold.debug.windowstolinux.appapi.linux.command.SshCommandFrameProtocol;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClient.Attribute;
import org.apache.sshd.sftp.client.SftpClient.Attributes;
import org.apache.sshd.sftp.common.SftpConstants;

/** Reads fixed launch-path metadata from the authenticated SSH subsystem. / 从已认证 SSH 子系统读取固定启动路径的元数据。 */
final class SshProtectedSystemProbe {
    /** Fixed non-link executables used at SSH framing and before ordinary probe admission. / 用于 SSH 帧启动和常规探测准入前的固定非链接程序。 */
    private static final List<String> SYSTEM_TOOLS = List.of("/usr/bin/env", "/usr/bin/stat", "/usr/bin/readlink");

    enum Profile {
        NONE,
        SYSTEM_PROBE,
        PRIVILEGE_CHECKED_SYSTEM_PROBE,
        HELPER_PERL_STARTUP;

        static Profile forScript(String script) {
            if (script.startsWith(SshCommandFrameProtocol.PROTECTED_SYSTEM_PROBE_HEADER)) return SYSTEM_PROBE;
            if (script.startsWith(SshCommandFrameProtocol.PRIVILEGE_CHECKED_SYSTEM_PROBE_HEADER))
                return PRIVILEGE_CHECKED_SYSTEM_PROBE;
            if (script.startsWith(SshCommandFrameProtocol.HELPER_PERL_STARTUP_HEADER)) return HELPER_PERL_STARTUP;
            return NONE;
        }
    }

    /** Keeps verification stateless between command requests. / 保持命令请求之间的校验无状态。 */
    private SshProtectedSystemProbe() {
    }

    /** Checks each ancestor before its children and recognizes the system merged-usr /bin link. / 先核验祖先再核验子项，并识别系统 merged-usr 的 /bin 链接。
     * @param sftp metadata channel on the original authenticated session / 原已认证会话上的元数据通道
     * @param profile launch paths required by the exact script header / 精确脚本首行要求校验的启动路径
     * @return whether every required fixed path met its metadata constraints / 每个必需固定路径是否满足元数据约束
     * @throws IOException if a metadata request fails / 元数据请求失败时 */
    static boolean verify(SftpClient sftp, Profile profile) throws IOException {
        if (sftp.getVersion() != SftpConstants.SFTP_V3) return false;
        boolean privilegeChecked = profile == Profile.PRIVILEGE_CHECKED_SYSTEM_PROBE;
        for (String directory : List.of("/", "/usr", "/usr/bin")) {
            if (!protectedDirectory(sftp.lstat(directory))) return false;
        }
        for (String executable : SYSTEM_TOOLS) {
            if (!protectedExecutable(sftp.lstat(executable), privilegeChecked)) return false;
        }
        if (!protectedBash(sftp, privilegeChecked)) return false;
        return switch (profile) {
            case HELPER_PERL_STARTUP -> protectedExecutable(sftp.lstat("/usr/bin/perl"), true);
            case PRIVILEGE_CHECKED_SYSTEM_PROBE -> protectedPrivilegeTools(sftp);
            case SYSTEM_PROBE -> true;
            case NONE -> false;
        };
    }

    private static boolean protectedPrivilegeTools(SftpClient sftp) throws IOException {
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
