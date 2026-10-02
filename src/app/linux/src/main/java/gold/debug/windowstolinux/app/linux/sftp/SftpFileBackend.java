package gold.debug.windowstolinux.app.linux.sftp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import gold.debug.windowstolinux.app.file.FileBackend;
import gold.debug.windowstolinux.appapi.file.*;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHPosixRenameExtension;
import org.apache.sshd.sftp.common.*;

/** File primitives on the original authenticated managed SSH channel. / 原认证受管 SSH 通道上的文件原语。 */
public final class SftpFileBackend implements FileBackend {
    private final ClientAccess clients;
    private final ServerRef server;
    /** Wraps a channel whose owning connection retains lifecycle responsibility. / 包装由所属连接负责生命周期的通道。 */
    public SftpFileBackend(SftpClient sftp, ServerRef server) { this(server, () -> Objects.requireNonNull(sftp)); }
    /** Binds a channel lookup refreshed by the original managed session. / 绑定由原受管会话刷新通道的查找器。
     * @param server server identity / 服务器身份
     * @param clients currently owned channel lookup / 当前所属通道查找器
     */
    public SftpFileBackend(ServerRef server, ClientAccess clients) { this.clients = Objects.requireNonNull(clients); this.server = Objects.requireNonNull(server); }
    /** Looks up the current channel after managed reauthentication. / 在受管重新认证后查找当前通道。
     * @return current owned channel / 当前所属通道
     * @throws IOException when the session cannot supply a channel / 会话不能提供通道时
     */
    private SftpClient client() throws IOException { return Objects.requireNonNull(clients.current(), "Managed SFTP channel"); }
    /** Supplies the current channel without exposing it to Mods. / 提供当前通道而不向 Mod 暴露。
     */
    @FunctionalInterface public interface ClientAccess {
        /** Supplies a scope-owned channel; the owning host closes it. / 提供作用域所属通道，由宿主关闭。
         * @return current owned channel / 当前所属通道
         * @throws IOException when lookup or refresh fails / 查找或刷新失败时
         */
        SftpClient current() throws IOException;
    }
    private String remote(FilePath path) { if (!path.server().equals(Optional.of(server))) throw new IllegalArgumentException("Foreign server path"); return path.path(); }
    /** Observes no-follow SFTP attributes. / 观测不跟随 SFTP 属性。 */
    @Override public Optional<FileInfo> stat(FilePath path) throws IOException {
        SftpClient sftp = client();
        try {
            var a = sftp.lstat(remote(path));
            FileInfo.Kind kind = a.isSymbolicLink() ? FileInfo.Kind.LINK : a.isDirectory() ? FileInfo.Kind.DIRECTORY : a.isRegularFile() ? FileInfo.Kind.FILE : FileInfo.Kind.OTHER;
            String link = kind == FileInfo.Kind.LINK ? sftp.readLink(path.path()) : null;
            Instant modified = a.getFlags().contains(SftpClient.Attribute.ModifyTime) ? a.getModifyTime().toInstant() : Instant.EPOCH;
            String identity = path.key() + ":" + kind + ":" + a.getPermissions() + ":" + a.getUserId() + ":" + a.getGroupId()
                + (kind == FileInfo.Kind.DIRECTORY ? "" : ":" + modified + ":" + a.getSize()) + (link == null ? "" : ":" + link);
            return Optional.of(new FileInfo(path, path, kind, Math.max(0, a.getSize()), modified, identity, Optional.ofNullable(link), List.of()));
        } catch (SftpException absent) { if (absent.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE) return Optional.empty(); throw absent; }
    }
    /** Lists a bounded no-follow directory. / 列举有界不跟随目录。 */
    @Override public List<String> list(FilePath path, int limit) throws IOException {
        SftpClient sftp = client();
        if (limit < 1 || limit > 100000) throw new IllegalArgumentException("Invalid directory limit");
        var names = new ArrayList<String>();
        try (SftpClient.CloseableHandle handle = sftp.openDir(remote(path))) {
            List<SftpClient.DirEntry> entries;
            while ((entries = sftp.readDir(handle)) != null) for (var entry : entries) {
                String name = entry.getFilename(); if (name.equals(".") || name.equals("..")) continue;
                if (name.contains("/") || name.contains("\\") || names.size() >= limit) throw new IOException("Directory capacity or name validation failed");
                names.add(name);
            }
        }
        names.sort(String::compareTo); return List.copyOf(names);
    }
    /** Opens ordinary content after lstat. / lstat 后打开普通内容。 */
    @Override public InputStream read(FilePath path) throws IOException {
        if (stat(path).orElseThrow(() -> new FileNotFoundException("Source absent")).kind() != FileInfo.Kind.FILE) throw new IOException("Read requires a regular file");
        return client().read(remote(path));
    }
    /** Exclusively creates a staged regular file. / 排他创建暂存普通文件。 */
    @Override public OutputStream create(FilePath path) throws IOException { return client().write(remote(path), EnumSet.of(SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Exclusive)); }
    /** Creates one directory. / 创建一个目录。 */
    @Override public void mkdir(FilePath path) throws IOException { client().mkdir(remote(path)); }
    /** Creates one symbolic link. / 创建一个符号链接。 */
    @Override public void link(FilePath path, String target) throws IOException { client().symLink(remote(path), target); }
    /** Publishes verified staging without predeletion. / 发布已验证暂存而不预删目标。 */
    @Override public void publish(FilePath staging, FilePath target, boolean replace) throws IOException {
        SftpClient sftp = client();
        if (!replace) { sftp.rename(remote(staging), remote(target)); return; }
        if (sftp.getVersion() >= 5) { sftp.rename(remote(staging), remote(target), SftpClient.CopyMode.Atomic, SftpClient.CopyMode.Overwrite); return; }
        var extension = sftp.getExtension(OpenSSHPosixRenameExtension.class);
        if (extension == null || !extension.isSupported()) throw new IOException("Server does not support atomic replacement");
        extension.posixRename(remote(staging), remote(target));
    }
    /** Removes the object without following links. / 删除对象而不跟随链接。 */
    @Override public void remove(FilePath path) throws IOException {
        FileInfo info = stat(path).orElseThrow(() -> new FileNotFoundException("Object absent"));
        if (info.kind() == FileInfo.Kind.DIRECTORY) client().rmdir(remote(path)); else client().remove(remote(path));
    }
    /** Supports account-home trash only; other mounts need explicit permanent-delete approval. / 仅支持账户主目录回收，其他挂载须明确永久删除批准。 */
    @Override public boolean canRecycle(FilePath path) throws IOException {
        SftpClient sftp = client();
        String home = sftp.canonicalPath(".");
        if (home == null || !home.startsWith("/") || home.equals("/")) return false;
        if (!remote(path).startsWith(home + "/") || path.path().startsWith(home + "/.local/share/Trash/")) return false;
        String trash = home + "/.local/share/Trash";
        for (String prefix : List.of(home + "/.local", home + "/.local/share", trash, trash + "/files", trash + "/info")) {
            var info = stat(FilePath.remote(server, prefix));
            if (info.isPresent() && info.orElseThrow().kind() != FileInfo.Kind.DIRECTORY) return false;
        }
        return true;
    }
    /** Persists a Trash specification record before moving the original object. / 移动原对象前保存 Trash 规范恢复记录。 */
    @Override public void recycle(FilePath path) throws IOException {
        SftpClient sftp = client();
        if (!canRecycle(path)) throw new IOException("Account-home recoverable trash is unavailable");
        String home = sftp.canonicalPath("."); String trash = home + "/.local/share/Trash";
        for (String directory : List.of(home + "/.local", home + "/.local/share", trash, trash + "/files", trash + "/info")) {
            FilePath p = FilePath.remote(server, directory);
            if (stat(p).isEmpty()) { sftp.mkdir(directory); sftp.setStat(directory, new SftpClient.Attributes().perms(0700)); }
            if (stat(p).orElseThrow().kind() != FileInfo.Kind.DIRECTORY) throw new IOException("Trash directory was redirected");
        }
        String name = "wtl-" + UUID.randomUUID();
        FilePath information = FilePath.remote(server, trash + "/info/" + name + ".trashinfo");
        String record = "[Trash Info]\nPath=" + encode(path.path()) + "\nDeletionDate=" + java.time.LocalDateTime.now().withNano(0) + "\n";
        try (OutputStream output = create(information)) { output.write(record.getBytes(StandardCharsets.UTF_8)); }
        FilePath recycled = FilePath.remote(server, trash + "/files/" + name);
        sftp.rename(remote(path), recycled.path());
        if (stat(path).isPresent() || stat(recycled).isEmpty()) throw new IOException("Trash publication requires reconciliation");
    }
    private static String encode(String text) {
        var result = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 255;
            if (c == '/' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_' || c == '.' || c == '~') result.append((char)c);
            else result.append('%').append(String.format(Locale.ROOT, "%02X", c));
        }
        return result.toString();
    }
    /** The managed connection closes the actual channel. / 实际通道由受管连接关闭。 */
    @Override public void close() { }
}
