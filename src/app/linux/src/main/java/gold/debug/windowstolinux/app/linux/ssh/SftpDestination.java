package gold.debug.windowstolinux.app.linux.ssh;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CancellationException;

import gold.debug.windowstolinux.app.windows.filesystem.*;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.task.CancellationToken;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHPosixRenameExtension;
import org.apache.sshd.sftp.common.*;

/** Selects and publishes a destination while preserving the old file throughout staging. / 选择并发布目标，在暂存期间完整保留旧文件。 */
final class SftpDestination {
    /** Actual authenticated SFTP channel. / 实际已认证 SFTP 通道。 */
    private final SftpClient sftp;

    /** Explicit original endpoints. / 明确原端点。 */
    private final TransferRequest request;

    /** Transfer direction. / 传输方向。 */
    private final boolean upload;

    /** Original cancellation signal. / 原取消信号。 */
    private final CancellationToken cancellation;

    /** Original transfer identity naming its temporary file. / 命名临时文件的原传输身份。 */
    private final UUID id;

    /** Actual selected remote endpoint. / 实际选定远端路径。 */
    private String remote;

    /** Actual selected local endpoint. / 实际选定本地路径。 */
    private Path local;

    /** Replacement decision retained only for the observed destination. / 仅为已观察目标保留的替换决定。 */
    private boolean overwrite;

    /** Original destination identity and metadata, absent when creating. / 原目标身份及元数据，新建时不存在。 */
    private TransferPlan.FileStamp expected;

    /** Binds destination selection to one actual transport. / 将目标选择绑定到一个实际传输。
     * @param sftp authenticated transport / 已认证传输
     * @param request reviewed endpoints / 已审核端点
     * @param upload transfer direction / 传输方向
     * @param id unique original transfer / 唯一原传输
     * @param cancellation original cancellation / 原取消 */
    SftpDestination(SftpClient sftp, TransferRequest request, boolean upload, UUID id, CancellationToken cancellation) {
        this.sftp = Objects.requireNonNull(sftp);
        this.request = Objects.requireNonNull(request);
        this.upload = upload;
        this.id = Objects.requireNonNull(id);
        this.cancellation = Objects.requireNonNull(cancellation);
        remote = request.remoteAbsolutePath();
        local = request.localPath();
    }

    /** Resolves a same-name conflict before creating a temporary file. / 创建临时文件前解决同名冲突。
     * @param size verified source length / 已验证源长度
     * @param choice actual user decision callback / 实际用户决定回调
     * @throws Exception if observations, user choice or replacement support are unavailable / 观测、用户选择或替换支持不可用时 */
    void select(long size, TransferConflictResolver choice) throws Exception {
        expected = observe();
        if (expected == null)
            return;
        var decision = Objects
                .requireNonNull(choice.resolve(new TransferConflict(request, upload, size, expected.size())));
        check();
        if (decision == TransferConflictChoice.TERMINATE)
            throw new CancellationException("File conflict terminated");
        if (decision == TransferConflictChoice.OVERWRITE) {
            overwrite = true;
            if (upload && sftp.getVersion() < 5 && !sftp.getExtension(OpenSSHPosixRenameExtension.class).isSupported())
                throw new IOException("Server does not advertise atomic replacement");
            return;
        }
        String original = upload ? remote.substring(remote.lastIndexOf('/') + 1) : local.getFileName().toString();
        for (int number = 1; number <= 10000; number++) {
            check();
            String name = alternative(original, number);
            if (upload)
                remote = request.remoteAbsolutePath().substring(0, request.remoteAbsolutePath().lastIndexOf('/') + 1)
                        + name;
            else
                local = request.localPath().resolveSibling(name);
            if (absent()) {
                expected = null;
                return;
            }
        }
        throw new IOException("No unused destination name was available");
    }

    /** Generates a bounded sibling name, preserving a normal extension. / 生成有界同目录名称，保留普通扩展名。
     * @param original original file name / 原文件名
     * @param number collision sequence / 冲突序号
     * @return candidate file name / 候选文件名 */
    private static String alternative(String original, int number) {
        int dot = original.lastIndexOf('.');
        String extension = dot > 0 && original.length() - dot <= 32 ? original.substring(dot) : "";
        String stem = extension.isEmpty() ? original : original.substring(0, dot);
        String suffix = " (" + number + ")" + extension;
        while ((stem + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 240 && !stem.isEmpty())
            stem = stem.substring(0, stem.offsetByCodePoints(stem.length(), -1));
        return stem + suffix;
    }

    /** Returns the same-directory remote staging name. / 返回同目录远端暂存名称。
     * @return temporary remote path / 临时远端路径 */
    String remoteTemporary() {
        return remote.substring(0, remote.lastIndexOf('/') + 1) + ".wtl-transfer-" + id + ".part";
    }

    /** Returns the same-directory local staging name. / 返回同目录本地暂存名称。
     * @return temporary local path / 临时本地路径 */
    Path localTemporary() {
        return local.resolveSibling(".wtl-transfer-" + id + ".part");
    }

    /** Returns the actual selected remote endpoint. / 返回实际选定远端路径。
     * @return remote endpoint / 远端路径 */
    String remote() {
        return remote;
    }

    /** Returns the actual selected local endpoint. / 返回实际选定本地路径。
     * @return local endpoint / 本地路径 */
    Path local() {
        return local;
    }

    /** Captures the selected endpoints and original replacement observation before mutation. / 变更前捕获选定端点及原替换观测。
     * @param source verified source observation / 已核验源观测
     * @return immutable original transfer plan / 不可变原传输计划 */
    TransferPlan plan(TransferPlan.FileStamp source) {
        return new TransferPlan(id, request, upload, local, remote, source, expected);
    }

    /** Rechecks the target and publishes only a completed sibling file. / 复核目标并仅发布已完成的同目录文件。
     * @throws IOException if the destination changed or publication failed / 目标变化或发布失败时 */
    void publish() throws IOException {
        check();
        if (!Objects.equals(expected, observe()))
            throw new IOException("Destination changed after conflict selection");
        if (!upload) {
            WindowsFilePublication.publish(localTemporary(), local, overwrite);
            return;
        }
        if (!overwrite) {
            sftp.rename(remoteTemporary(), remote);
            return;
        }
        if (sftp.getVersion() >= 5)
            sftp.rename(remoteTemporary(), remote, SftpClient.CopyMode.Atomic, SftpClient.CopyMode.Overwrite);
        else
            sftp.getExtension(OpenSSHPosixRenameExtension.class).posixRename(remoteTemporary(), remote);
    }

    /** Observes an ordinary target without following a final link. / 不跟随末级链接，观察普通目标。
     * @return current target observation, or null only for confirmed absence / 当前目标观测，仅确认缺失时为 null
     * @throws IOException if the target is linked, unreadable or not regular / 目标为链接、不可读或非普通文件时 */
    private TransferPlan.FileStamp observe() throws IOException {
        check();
        if (upload) {
            try {
                var attrs = sftp.lstat(remote);
                if (!attrs.isRegularFile() || attrs.isSymbolicLink()
                        || !attrs.getFlags().contains(SftpClient.Attribute.Size)
                        || !attrs.getFlags().contains(SftpClient.Attribute.ModifyTime)
                        || !remote.equals(sftp.canonicalPath(remote)))
                    throw new IOException("Remote destination is not a stable regular file");
                return new TransferPlan.FileStamp("remote", attrs.getSize(), attrs.getModifyTime().toString(),
                        content(attrs.getSize()));
            } catch (SftpException absent) {
                if (absent.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE)
                    return null;
                throw absent;
            }
        }
        try {
            var attrs = Files.readAttributes(local, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile() || attrs.isOther() || attrs.isSymbolicLink())
                throw new IOException("Local destination is not a regular file");
            return new TransferPlan.FileStamp(WindowsFileIdentity.read(local), attrs.size(),
                    attrs.lastModifiedTime().toString(), content(attrs.size()));
        } catch (NoSuchFileException absent) {
            return null;
        }
    }

    /** Distinguishes an unused name from any occupied file, directory or link. / 区分未占用名称与任意已有文件、目录或链接。
     * @return true only for confirmed absence / 仅确认缺失时为真
     * @throws IOException if existence cannot be established / 无法确立存在性时 */
    private boolean absent() throws IOException {
        if (upload) {
            try {
                sftp.lstat(remote);
                return false;
            } catch (SftpException failure) {
                if (failure.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE)
                    return true;
                throw failure;
            }
        }
        try {
            Files.readAttributes(local, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return false;
        } catch (NoSuchFileException missing) {
            return true;
        }
    }

    /** Binds replacement to observed content even when timestamps are coarse or preserved. / 即使时间戳精度低或被保留，也将替换绑定到已观察内容。
     * @param size observed exact extent / 已观察精确范围
     * @return content digest / 内容摘要
     * @throws IOException if reading or length verification fails / 读取或长度核验失败时 */
    private String content(long size) throws IOException {
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        try (var input = upload ? sftp.read(remote) : Files.newInputStream(local, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[65536];
            long read = 0;
            while (read < size) {
                check();
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, size - read));
                if (count < 0)
                    throw new IOException("Destination content ended early");
                if (count == 0)
                    continue;
                digest.update(buffer, 0, count);
                read += count;
            }
            check();
            if (input.read() != -1)
                throw new IOException("Destination content grew");
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Stops before any new target operation after cancellation. / 取消后在任何新目标操作前停止。 */
    private void check() {
        if (cancellation.requested() || Thread.currentThread().isInterrupted())
            throw new CancellationException("Transfer destination cancelled");
    }
}
