package gold.debug.windowstolinux.app.file;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import gold.debug.windowstolinux.appapi.file.*;
import gold.debug.windowstolinux.app.windows.file.WindowsRecycle;
import gold.debug.windowstolinux.app.windows.file.WindowsLinks;
import gold.debug.windowstolinux.app.windows.file.WindowsFileIdentity;

/** Actual process-account filesystem access without an artificial global root. / 当前进程账户文件访问，不附加全局根限制。 */
public final class LocalFileBackend implements FileBackend {
    private static Path local(FilePath path) { if (path.server().isPresent()) throw new IllegalArgumentException("Not a local path"); return Path.of(path.path()); }
    /** Observes local no-follow attributes. / 观测本机不跟随属性。 */
    @Override public Optional<FileInfo> stat(FilePath path) throws IOException {
        return observe(path,true);
    }
    /** Observes traversable ancestors without demanding extra native-handle rights for ordinary directories. / 观测可遍历祖先，不额外要求普通目录的原生句柄权限。
     * @param path ancestor position / 祖先位置
     * @return traversal observation; links retain native identity / 遍历观测，链接保留原生身份
     * @throws IOException when no-follow attributes fail / 不跟随属性读取失败时
     */
    @Override public Optional<FileInfo> ancestor(FilePath path) throws IOException { return observe(path,false); }
    /** Reads object attributes and obtains native identity for all effect targets and links. / 读取对象属性，为所有副作用目标及链接取得原生身份。
     * @param path actual position / 实际位置
     * @param requireIdentity whether this is an effect target / 是否为副作用目标
     * @return no-follow observation / 不跟随观测
     * @throws IOException when attributes or required identity fail / 属性或所需身份读取失败时
     */
    private Optional<FileInfo> observe(FilePath path,boolean requireIdentity) throws IOException {
        Path p = local(path);
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Optional<String> linkTarget=WindowsLinks.target(p);
            boolean link = linkTarget.isPresent();
            String target = linkTarget.orElse(null);
            FileInfo.Kind kind = link ? FileInfo.Kind.LINK : a.isDirectory() ? FileInfo.Kind.DIRECTORY : a.isRegularFile() ? FileInfo.Kind.FILE : FileInfo.Kind.OTHER;
            String objectId = !requireIdentity && kind == FileInfo.Kind.DIRECTORY ? "traversal:"+path.key()
                : com.sun.jna.Platform.isWindows() ? WindowsFileIdentity.read(p) : Objects.toString(a.fileKey(), null);
            if (objectId == null) throw new IOException("Stable object identity is unavailable");
            String identity = objectId + ":" + a.creationTime().toMillis()
                + (a.isDirectory() && !link ? "" : ":" + a.lastModifiedTime().toMillis() + ":" + a.size());
            return Optional.of(new FileInfo(path, path, kind, Math.max(0, a.size()), a.lastModifiedTime().toInstant(), identity, Optional.ofNullable(target), List.of()));
        } catch (NoSuchFileException absent) { return Optional.empty(); }
    }
    /** Lists immediate names with a hard bound. / 以硬上限列举直接名称。 */
    @Override public List<String> list(FilePath path, int limit) throws IOException {
        if (limit < 1 || limit > 100000) throw new IllegalArgumentException("Invalid directory limit");
        var names = new ArrayList<String>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(local(path))) {
            for (Path p : stream) { if (names.size() == limit) throw new IOException("Directory capacity exceeded"); names.add(p.getFileName().toString()); }
        }
        names.sort(String::compareTo); return List.copyOf(names);
    }
    /** Opens no-follow regular content. / 打开不跟随链接的普通内容。 */
    @Override public InputStream read(FilePath path) throws IOException { return Files.newInputStream(local(path), LinkOption.NOFOLLOW_LINKS); }
    /** Creates exclusive no-follow staging. / 创建排他不跟随暂存。 */
    @Override public OutputStream create(FilePath path) throws IOException { return Files.newOutputStream(local(path), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); }
    /** Creates an empty directory. / 创建空目录。 */
    @Override public void mkdir(FilePath path) throws IOException { Files.createDirectory(local(path)); }
    /** Creates a symbolic link. / 创建符号链接。 */
    @Override public void link(FilePath path, String target) throws IOException { WindowsLinks.create(local(path),Path.of(target)); }
    /** Uses atomic replacement and refuses unsupported publication. / 使用原子替换，拒绝不支持的发布。 */
    @Override public void publish(FilePath staging, FilePath target, boolean replace) throws IOException {
        if (!replace && Files.exists(local(target), LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(target.path());
        if(replace && com.sun.jna.Platform.isWindows() && WindowsLinks.target(local(staging)).isPresent()) {
            var attributes=Files.readAttributes(local(target),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(attributes.isDirectory()&&!attributes.isSymbolicLink()&&WindowsLinks.target(local(target)).isPresent()) {
                Path text=Path.of(WindowsLinks.target(local(staging)).orElseThrow());
                WindowsLinks.retargetDirectoryJunction(local(target),text.isAbsolute()?text:local(staging).getParent().resolve(text).normalize());
                Files.delete(local(staging));return;
            }
        }
        if (replace) Files.move(local(staging), local(target), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        else Files.move(local(staging), local(target), StandardCopyOption.ATOMIC_MOVE);
    }
    /** Deletes only this object. / 仅删除此对象。 */
    @Override public void remove(FilePath path) throws IOException { Files.delete(local(path)); }
    /** Checks system trash capability for this location. / 检查当前位置系统回收能力。 */
    @Override public boolean canRecycle(FilePath path) throws IOException { return WindowsRecycle.supports(local(path)); }
    /** Invokes the system's recoverable recycle operation. / 调用系统可恢复回收操作。 */
    @Override public void recycle(FilePath path) throws IOException { WindowsRecycle.recycle(local(path)); }
    /** No resources remain between local operations. / 本机操作之间无保留资源。 */
    @Override public void close() { }
}
