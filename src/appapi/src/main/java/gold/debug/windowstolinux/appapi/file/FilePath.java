package gold.debug.windowstolinux.appapi.file;

import java.nio.file.Path;
import java.util.Optional;
import gold.debug.windowstolinux.appapi.server.ServerRef;

/** Absolute location with an explicit host identity. / 带明确主机身份的绝对位置。 */
public record FilePath(Optional<ServerRef> server, String path) {
    /** Validates a local or Linux path without interpreting a remote path as Windows. / 分别校验本机和 Linux 路径。 */
    public FilePath {
        java.util.Objects.requireNonNull(server);
        if (path == null || path.isBlank() || path.length() > 4096 || path.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid file path");
        if (server.isEmpty()) {
            Path local = Path.of(path);
            if (!local.isAbsolute()) throw new IllegalArgumentException("An absolute local path is required");
            path = local.normalize().toString();
        } else {
            if (!path.startsWith("/") || path.contains("\\")) throw new IllegalArgumentException("An absolute Linux path is required");
            var names = new java.util.ArrayDeque<String>();
            for (String name : path.split("/")) {
                if (name.isEmpty() || name.equals(".")) continue;
                if (name.equals("..")) { if (!names.isEmpty()) names.removeLast(); }
                else names.addLast(name);
            }
            path = "/" + String.join("/", names);
        }
    }
    /** Creates a local location. / 创建本机位置。 */
    public static FilePath local(Path path) { return new FilePath(Optional.empty(), path.toAbsolutePath().toString()); }
    /** Creates a server-bound Linux location. / 创建服务器绑定的 Linux 位置。 */
    public static FilePath remote(ServerRef server, String path) { return new FilePath(Optional.of(server), path); }
    /** Returns the parent location. / 返回父位置。 */
    public FilePath parent() {
        if (server.isEmpty()) {
            Path p = Path.of(path).getParent();
            if (p == null) throw new IllegalArgumentException("A root has no parent");
            return local(p);
        }
        if (path.equals("/")) throw new IllegalArgumentException("A root has no parent");
        int i = path.lastIndexOf('/');
        return remote(server.orElseThrow(), i == 0 ? "/" : path.substring(0, i));
    }
    /** Resolves a single child name. / 解析单个子项名称。 */
    public FilePath child(String name) {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals(".."))
            throw new IllegalArgumentException("A single child name is required");
        return server.isEmpty() ? local(Path.of(path).resolve(name)) : remote(server.orElseThrow(), (path.equals("/") ? "" : path) + "/" + name);
    }
    /** Returns the final path name. / 返回路径末段名称。 */
    public String name() { return server.isEmpty() ? Path.of(path).getFileName().toString() : path.substring(path.lastIndexOf('/') + 1); }
    /** Returns a host-qualified stable location key. / 返回带主机的稳定位置键。 */
    public String key() { return server.map(s -> "server:" + s.id()).orElse("local") + ":" + path; }
}
