package gold.debug.windowstolinux.app.linux.sftp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

import gold.debug.windowstolinux.app.file.FileEngine;
import gold.debug.windowstolinux.app.file.model.FileBackend;
import gold.debug.windowstolinux.app.file.model.FileRecycleReceipt;
import gold.debug.windowstolinux.appapi.file.model.FileInfo;
import gold.debug.windowstolinux.appapi.file.model.FilePath;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHPosixRenameExtension;
import org.apache.sshd.sftp.common.*;

/** File primitives on the original authenticated managed SSH channel. / 原认证受管 SSH 通道上的文件原语。 */
public final class SftpFileBackend implements FileBackend {
    /** Scope-owned channel lookup refreshed by original authentication / 由原认证刷新且属于作用域的通道查找器.
     */
    private final ClientAccess clients;

    /** authenticated server identity / 已认证服务器身份.
     */
    private final ServerId server;

    /** Wraps a channel whose owning connection retains lifecycle responsibility. / 包装由所属连接负责生命周期的通道。
     * @param sftp original managed authenticated channel / 原受管认证通道
     * @param server authenticated server identity / 已认证服务器身份
     */
    public SftpFileBackend(SftpClient sftp, ServerId server) {
        this(server, () -> Objects.requireNonNull(sftp));
    }

    /** Binds a channel lookup refreshed by the original managed session. / 绑定由原受管会话刷新通道的查找器。
     * @param server server identity / 服务器身份
     * @param clients currently owned channel lookup / 当前所属通道查找器
     */
    public SftpFileBackend(ServerId server, ClientAccess clients) {
        this.clients = Objects.requireNonNull(clients);
        this.server = Objects.requireNonNull(server);
    }

    /** Looks up the current channel after managed reauthentication. / 在受管重新认证后查找当前通道。
     * @return current owned channel / 当前所属通道
     * @throws IOException when the session cannot supply a channel / 会话不能提供通道时
     */
    private SftpClient client() throws IOException {
        return Objects.requireNonNull(clients.current(), "Managed SFTP channel");
    }

    /** Supplies the current channel without exposing it to Mods. / 提供当前通道而不向 Mod 暴露。
     */
    @FunctionalInterface
    public interface ClientAccess {
        /** Supplies a scope-owned channel; the owning host closes it. / 提供作用域所属通道，由宿主关闭。
         * @return current owned channel / 当前所属通道
         * @throws IOException when lookup or refresh fails / 查找或刷新失败时
         */
        SftpClient current() throws IOException;
    }

    /** Validates that a location belongs to the authenticated server. / 校验位置属于已认证服务器。
     * @param path exact host-qualified position / 精确带主机位置
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     */
    private String remote(FilePath path) {
        if (!path.server().equals(Optional.of(server))) throw new IllegalArgumentException("Foreign server path");
        return path.path();
    }

    /** Observes no-follow SFTP attributes. / 观测不跟随 SFTP 属性。
     * @param path exact host-qualified position / 精确带主机位置
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    @Override
    public Optional<FileInfo> stat(FilePath path) throws IOException {
        SftpClient sftp = client();
        try {
            var a = sftp.lstat(remote(path));
            FileInfo.Kind kind = a.isSymbolicLink() ? FileInfo.Kind.LINK : a.isDirectory() ? FileInfo.Kind.DIRECTORY : a.isRegularFile() ? FileInfo.Kind.FILE : FileInfo.Kind.OTHER;
            String link = kind == FileInfo.Kind.LINK ? sftp.readLink(path.path()) : null;
            Instant modified = a.getFlags().contains(SftpClient.Attribute.ModifyTime) ? a.getModifyTime().toInstant() : Instant.EPOCH;
            String identity = path.key() + ":" + kind + ":" + a.getPermissions() + ":" + a.getUserId() + ":" + a.getGroupId()
                + (kind == FileInfo.Kind.DIRECTORY ? "" : ":" + modified + ":" + a.getSize()) + (link == null ? "" : ":" + link);
            return Optional.of(new FileInfo(path, path, kind, Math.max(0, a.getSize()), modified, identity, Optional.ofNullable(link), List.of()));
        } catch (SftpException absent) {
            if (absent.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE) return Optional.empty();
            throw absent;
        }
    }

    /** Lists a bounded no-follow directory. / 列举有界不跟随目录。
     * @param path exact host-qualified position / 精确带主机位置
     * @param limit maximum accepted immediate entries or bytes / 允许的直接子项或字节上限
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    @Override
    public List<String> list(FilePath path, int limit) throws IOException {
        SftpClient sftp = client();
        if (limit < 1 || limit > 100000) throw new IllegalArgumentException("Invalid directory limit");
        var names = new ArrayList<String>();
        try (SftpClient.CloseableHandle handle = sftp.openDir(remote(path))) {
            List<SftpClient.DirEntry> entries;
            while ((entries = sftp.readDir(handle)) != null) for (var entry : entries) {
                String name = entry.getFilename();
                if (name.equals(".") || name.equals("..")) continue;
                if (name.contains("/") || name.contains("\\") || names.size() >= limit)
                    throw new IOException("Directory capacity or name validation failed");
                names.add(name);
            }
        }
        names.sort(String::compareTo);
        return List.copyOf(names);
    }

    /** Opens ordinary content after lstat. / lstat 后打开普通内容。
     * @param path exact host-qualified position / 精确带主机位置
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    @Override
    public InputStream read(FilePath path) throws IOException {
        if (stat(path).orElseThrow(() -> new FileNotFoundException("Source absent")).kind() != FileInfo.Kind.FILE)
            throw new IOException("Read requires a regular file");
        return client().read(remote(path));
    }

    /** Exclusively creates a staged regular file. / 排他创建暂存普通文件。
     * @param path exact host-qualified position / 精确带主机位置
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    @Override
    public OutputStream create(FilePath path) throws IOException {
        return client().write(remote(path), EnumSet.of(SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Exclusive));
    }

    /** Creates one directory. / 创建一个目录。
     * @param path exact host-qualified position / 精确带主机位置
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    @Override
    public void mkdir(FilePath path) throws IOException {
        client().mkdir(remote(path));
    }

    /** Creates one symbolic link. / 创建一个符号链接。
     * @param path exact host-qualified position / 精确带主机位置
     * @param target exact planned destination or link target / 精确预定位置或链接目标
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    @Override
    public void link(FilePath path, String target) throws IOException {
        client().symLink(remote(path), target);
    }

    /** Publishes verified staging without predeletion. / 发布已验证暂存而不预删目标。
     * @param staging exclusively owned staged object / 排他所属暂存对象
     * @param target exact planned destination or link target / 精确预定位置或链接目标
     * @param replace whether exact replacement is approved / 是否批准精确替换
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    @Override
    public void publish(FilePath staging, FilePath target, boolean replace) throws IOException {
        SftpClient sftp = client();
        if (!replace) {
            sftp.rename(remote(staging), remote(target));
            return;
        }
        if (sftp.getVersion() >= 5) {
            sftp.rename(remote(staging), remote(target), SftpClient.CopyMode.Atomic, SftpClient.CopyMode.Overwrite);
            return;
        }
        var extension = sftp.getExtension(OpenSSHPosixRenameExtension.class);
        if (extension == null || !extension.isSupported())
            throw new IOException("Server does not support atomic replacement");
        extension.posixRename(remote(staging), remote(target));
    }

    /** Removes the object without following links. / 删除对象而不跟随链接。
     * @param path exact host-qualified position / 精确带主机位置
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    @Override
    public void remove(FilePath path) throws IOException {
        FileInfo info = stat(path).orElseThrow(() -> new FileNotFoundException("Object absent"));
        if (info.kind() == FileInfo.Kind.DIRECTORY) client().rmdir(remote(path));
        else client().remove(remote(path));
    }

    /** Supports account-home trash only; other mounts need explicit permanent-delete approval. / 仅支持账户主目录回收，其他挂载须明确永久删除批准。
     * @param path exact host-qualified position / 精确带主机位置
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    @Override
    public boolean canRecycle(FilePath path) throws IOException {
        SftpClient sftp = client();
        String home = sftp.canonicalPath(".");
        if (home == null || !home.startsWith("/") || home.equals("/")) return false;
        if (!remote(path).startsWith(home + "/") || path.path().startsWith(home + "/.local/share/Trash/")) return false;
        String trash = home + "/.local/share/Trash";
        if (trash.equals(path.path()) || trash.startsWith(path.path() + "/")) return false;
        var account = sftp.lstat(home);
        if (!safeDirectory(account, account, false)) return false;
        for (String prefix : List.of(home + "/.local", home + "/.local/share", trash, trash + "/files", trash + "/info")) {
            var attributes = attributes(sftp, prefix);
            if (attributes.isPresent() && !safeDirectory(account, attributes.orElseThrow(), prefix.startsWith(trash)))
                return false;
        }
        return true;
    }

    /** Persists a Trash specification record before moving the original object. / 移动原对象前保存 Trash 规范恢复记录。
     * @param path exact host-qualified position / 精确带主机位置
     * @return actual Trash object and separate restoration metadata identities / 实际 Trash 对象及独立恢复元数据身份
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    @Override
    public FileRecycleReceipt recycle(FilePath path) throws IOException {
        SftpClient sftp = client();
        if (!canRecycle(path)) throw new IOException("Account-home recoverable trash is unavailable");
        String home = sftp.canonicalPath(".");
        String trash = home + "/.local/share/Trash";
        var account = sftp.lstat(home);
        for (String directory : List.of(home + "/.local", home + "/.local/share", trash, trash + "/files", trash + "/info")) {
            FilePath p = FilePath.remote(server, directory);
            if (stat(p).isEmpty()) {
                sftp.mkdir(directory);
                sftp.setStat(directory, new SftpClient.Attributes().perms(0700));
            }
            if (!safeDirectory(account, sftp.lstat(directory), directory.startsWith(trash)))
                throw new IOException("Trash directory ownership, private mode or no-follow identity is unproved");
        }
        String name = "wtl-" + UUID.randomUUID();
        FilePath information = FilePath.remote(server, trash + "/info/" + name + ".trashinfo");
        String record = "[Trash Info]\nPath=" + encode(path.path()) + "\nDeletionDate=" + java.time.LocalDateTime.now().withNano(0) + "\n";
        try (OutputStream output = create(information)) {
            output.write(record.getBytes(StandardCharsets.UTF_8));
        }
        sftp.setStat(information.path(), new SftpClient.Attributes().perms(0600));
        var metadata = sftp.lstat(information.path());
        if (!metadata.isRegularFile() || !metadata.getFlags().contains(SftpClient.Attribute.Perms) || (metadata.getPermissions() & 0777) != 0600 || !sameOwner(account, metadata))
            throw new IOException("Private recovery metadata cannot be verified");
        FilePath recycled = FilePath.remote(server, trash + "/files/" + name);
        if (stat(recycled).isPresent()) throw new IOException("Recovery destination already exists");
        if (!canRecycle(path)) throw new IOException("Trash hierarchy changed before publication");
        sftp.rename(remote(path), recycled.path());
        if (stat(path).isPresent() || stat(recycled).isEmpty())
            throw new IOException("Trash publication requires reconciliation");
        return new FileRecycleReceipt(recycled, stat(recycled).orElseThrow().identity(), Optional.of(information), Optional.of(stat(information).orElseThrow().identity()));
    }

    /** Requires account ownership and rejects writable or redirected recovery directories. / 要求账户归属并拒绝可被其他人写入或重定向的恢复目录。
     * @param account canonical home ownership evidence / 规范主目录归属依据
     * @param directory no-follow directory attributes / 不跟随目录属性
     * @param privateMode whether exact mode 0700 is required / 是否要求精确权限 0700
     * @return whether owner, directory kind and permissions are actually proved / 归属、目录类型及权限是否实际已证明 */
    private static boolean safeDirectory(SftpClient.Attributes account, SftpClient.Attributes directory, boolean privateMode) {
        return directory.isDirectory() && !directory.isSymbolicLink() && directory.getFlags().contains(SftpClient.Attribute.Perms) && sameOwner(account, directory)
            && (privateMode ? (directory.getPermissions() & 0777) == 0700 : (directory.getPermissions() & 0022) == 0);
    }

    /** Compares numeric owners on SFTP 3 or explicit owner names on newer protocol versions. / 在 SFTP 3 比较数字属主，在较新协议比较明确属主名称。
     * @param account canonical account home attributes / 规范账户主目录属性
     * @param item recovery item attributes / 恢复项属性
     * @return exact owner equality only when both required protocol fields exist / 仅两侧所需协议字段存在时的精确属主相等 */
    private static boolean sameOwner(SftpClient.Attributes account, SftpClient.Attributes item) {
        if (account.getFlags().contains(SftpClient.Attribute.UidGid) && item.getFlags().contains(SftpClient.Attribute.UidGid))
            return account.getUserId() == item.getUserId();
        return account.getFlags().contains(SftpClient.Attribute.OwnerGroup) && item.getFlags().contains(SftpClient.Attribute.OwnerGroup)
            && account.getOwner() != null && !account.getOwner().isBlank() && account.getOwner().equals(item.getOwner());
    }

    /** Treats only a genuine no-such-file reply as absent recovery hierarchy. / 仅将真实文件不存在响应视作恢复层级缺失。
     * @param sftp current managed authenticated channel / 当前受管认证通道
     * @param path exact remote recovery path / 精确远端恢复路径
     * @return no-follow attributes or proved absence / 不跟随属性或已证明缺失
     * @throws IOException if permission or protocol failure prevents observation / 权限或协议失败阻止观测时 */
    private static Optional<SftpClient.Attributes> attributes(SftpClient sftp, String path) throws IOException {
        try {
            return Optional.of(sftp.lstat(path));
        } catch (SftpException failure) {
            if (failure.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE) return Optional.empty();
            throw failure;
        }
    }

    /** Percent-encodes UTF-8 paths for recoverable Trash metadata. / 为可恢复 Trash 元数据百分号编码 UTF-8 路径。
     * @param text validated link or filter text / 已校验链接或筛选文本
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     */
    private static String encode(String text) {
        var result = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 255;
            if (c == '/' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_' || c == '.' || c == '~')
                result.append((char) c);
            else result.append('%').append(String.format(Locale.ROOT, "%02X", c));
        }
        return result.toString();
    }

    /** Verifies bounded restoration metadata through the shared file engine. / 通过统一文件引擎核验有界恢复元数据。
     * @param original original removed object position / 原被移除对象位置
     * @param receipt observed recovery object and metadata / 已观测恢复对象及元数据
     * @return whether metadata describes the exact original position / 元数据是否描述精确原位置
     * @throws IOException if metadata is unreadable, changes or exceeds its bound / 元数据无法读取、发生变化或超过界限时 */
    @Override
    public boolean recoveryInformationMatches(FilePath original, FileRecycleReceipt receipt) throws IOException {
        return new FileEngine(this).recoveryInformationMatches(original, receipt);
    }

    /** The managed connection closes the actual channel. / 实际通道由受管连接关闭。 */
    @Override
    public void close() {
    }
}
