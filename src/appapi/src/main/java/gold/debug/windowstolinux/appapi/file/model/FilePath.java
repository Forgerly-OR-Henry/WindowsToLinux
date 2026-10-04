package gold.debug.windowstolinux.appapi.file.model;

import java.nio.file.Path;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.server.model.ServerId;

/** Absolute location with an explicit host identity. / 带明确主机身份的绝对位置。
 * @param server server identity, empty for this account's local host; metadata alone grants no server admission / 服务器身份，空值表示当前账户本机，元数据本身不授予服务器准入
 * @param path absolute local or Linux path normalized without filesystem access / 不访问文件系统地规范化的绝对本机或 Linux 路径
 */
public record FilePath(Optional<ServerId> server, String path) {
    /** Validates a local or Linux path without interpreting a remote path as Windows. / 分别校验本机和 Linux 路径。
     * @param server server identity, empty for this account's local host; metadata alone grants no server admission / 服务器身份，空值表示当前账户本机，元数据本身不授予服务器准入
     * @param path absolute local or Linux path normalized without filesystem access / 不访问文件系统地规范化的绝对本机或 Linux 路径
     */
    public FilePath {
        java.util.Objects.requireNonNull(server);
        if (path == null || path.isBlank() || path.length() > 4096 || path.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid file path");
        if (server.isEmpty()) {
            Path local = Path.of(path);
            if (!local.isAbsolute()) throw new IllegalArgumentException("An absolute local path is required");
            path = local.normalize().toString();
        } else {
            if (!path.startsWith("/") || path.contains("\\"))
                throw new IllegalArgumentException("An absolute Linux path is required");
            var names = new java.util.ArrayDeque<String>();
            for (String name : path.split("/")) {
                if (name.isEmpty() || name.equals(".")) continue;
                if (name.equals("..")) {
                    if (!names.isEmpty()) names.removeLast();
                } else names.addLast(name);
            }
            path = "/" + String.join("/", names);
        }
    }

    /** Creates a local location. / 创建本机位置。
     * @param path local path, converted to an absolute path against the process directory / 相对进程目录转换为绝对路径的本机路径
     * @return normalized absolute local position with no server identity / 不带服务器身份的规范绝对本机位置
     */
    public static FilePath local(Path path) {
        return new FilePath(Optional.empty(), path.toAbsolutePath().toString());
    }

    /** Creates a server-bound Linux location. / 创建服务器绑定的 Linux 位置。
     * @param server server identity, empty for this account's local host; metadata alone grants no server admission / 服务器身份，空值表示当前账户本机，元数据本身不授予服务器准入
     * @param path absolute local or Linux path normalized without filesystem access / 不访问文件系统地规范化的绝对本机或 Linux 路径
     * @return normalized absolute Linux position bound to the supplied server identity / 绑定给定服务器身份的规范绝对 Linux 位置
     */
    public static FilePath remote(ServerId server, String path) {
        return new FilePath(Optional.of(server), path);
    }

    /** Returns the parent location. / 返回父位置。
     * @return parent on the same host; a host root has no parent and is rejected / 同主机父位置，主机根无父位置并被拒绝
     */
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

    /** Resolves a single child name on the same host. / 解析同主机的单个子项名称。
     * @param name single name without separators, dot or parent traversal; local names must have no root or drive qualifier / 不含分隔符且不为 . 或 .. 的单个名称；本机名称不得带根或盘符
     * @return child position on the same host without checking whether it exists / 同主机子项位置，不检查其是否存在
     * @throws IllegalArgumentException if the name does not identify a direct child on this host / 名称不能表示此主机上的直接子项时
     */
    public FilePath child(String name) {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals(".."))
            throw new IllegalArgumentException("A single child name is required");
        if (server.isPresent())
            return remote(server.orElseThrow(), (path.equals("/") ? "" : path) + "/" + name);
        Path child = Path.of(name);
        Path parent = Path.of(path);
        if (child.getRoot() != null || child.isAbsolute() || child.getNameCount() != 1)
            throw new IllegalArgumentException("An unqualified child name is required");
        Path resolved = parent.resolve(child).normalize();
        if (!parent.equals(resolved.getParent()))
            throw new IllegalArgumentException("A direct child on the same host is required");
        return local(resolved);
    }

    /** Returns the final path name. / 返回路径末段名称。
     * @return final name, or an empty string for a host root / 末段名称，主机根返回空串
     */
    public String name() {
        return server.isEmpty() ? Optional.ofNullable(Path.of(path).getFileName()).map(Path::toString).orElse("") : path.substring(path.lastIndexOf('/') + 1);
    }

    /** Returns a host-qualified stable location key. / 返回带主机的稳定位置键。
     * @return normalized location prefixed with local or server identity, without granting authority / 带本机或服务器身份前缀的规范位置，不授予权限
     */
    public String key() {
        return server.map(s -> "server:" + s.id()).orElse("local") + ":" + path;
    }
}
