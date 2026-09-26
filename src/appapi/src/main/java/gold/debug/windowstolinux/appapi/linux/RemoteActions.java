package gold.debug.windowstolinux.appapi.linux;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.ActionIntent;

/** Produces exact review descriptions shared by authors and the platform transport. / 生成作者及平台传输共用的精确审核描述。 */
public final class RemoteActions {
    /** Prevents utility construction. / 防止构造工具实例。 */
    private RemoteActions() {
    }

    /**
     * Describes a command including its entire script and immutable input digest. / 描述命令，包含完整脚本及不可变输入摘要。
     * @param server selected saved server / 选定的已保存服务器
     * @param revision author-owned business revision requiring this approval / 需要此次审批的作者所属业务修订
     * @param request exact immutable command request / 精确不可变命令请求
     * @return intent accepted by the public remote session / 公开远端会话接受的意图
     */
    public static ActionIntent command(ServerRef server, String revision, CommandRequest request) {
        var input = request.stdin();
        String digest;
        try {
            digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("JDK SHA-256 unavailable", unavailable);
        } finally {
            Arrays.fill(input, (byte) 0);
        }
        String action = "WTL-SSH-COMMAND-1\ntimeout=" + request.timeout() + "\noutputLimitBytes="
                + request.outputLimitBytes() + "\nstdinBytes=" + input.length + "\nstdinSha256=" + digest
                + "\nscript:\n" + request.script();
        return new ActionIntent("platform.ssh.execute", List.of(server.id()), server.id(), revision, action,
                "Execute the exact reviewed script on the selected server.");
    }

    /**
     * Describes exclusive file creation with exact paths, direction, size limit and source digest. / 描述排他文件创建，包含精确路径、方向、容量上限及源摘要。
     * @param server selected saved server / 选定的已保存服务器
     * @param revision author-owned business revision / 作者所属业务修订
     * @param request exact transfer endpoints and limit / 精确传输端点及上限
     * @param sha256 lowercase SHA-256 of the reviewed source / 已审核源内容的小写 SHA-256
     * @param upload true for local-to-remote transfer / 本地传向远端时为真
     * @return intent accepted by the public remote session / 公开远端会话接受的意图
     */
    public static ActionIntent transfer(ServerRef server, String revision, TransferRequest request, String sha256,
            boolean upload) {
        if (!Objects.requireNonNull(sha256).matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Transfer approval requires a lowercase SHA-256");
        var local = request.localPath();
        if (!local.isAbsolute() || !local.normalize().equals(local))
            throw new IllegalArgumentException("Transfer approval requires an absolute normalized local path");
        String action = "WTL-SFTP-1\nsha256=" + sha256 + "\nmaxBytes=" + request.maxBytes() + "\nlocalPathCharacters="
                + local.toString().length() + "\n" + local + "\nremotePathCharacters="
                + request.remoteAbsolutePath().length() + "\n" + request.remoteAbsolutePath();
        return new ActionIntent(upload ? "platform.sftp.upload" : "platform.sftp.download", List.of(server.id()),
                server.id(), revision, action,
                upload
                        ? "Upload by creating a new remote file; existing files are preserved."
                        : "Download by creating a new local file; existing files are preserved.");
    }
}
