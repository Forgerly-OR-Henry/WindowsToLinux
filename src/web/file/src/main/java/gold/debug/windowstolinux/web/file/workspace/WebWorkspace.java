package gold.debug.windowstolinux.web.file.workspace;

import gold.debug.windowstolinux.web.file.quota.UploadQuota;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.Locale;

/**
 * Owns workspace storage, streaming quotas and strict path checks; uploaded code is never executed.
 * <p>持有工作区存储，执行流式配额及严格路径检查；绝不执行已上传代码。
 */
public final class WebWorkspace {
    /**
     * Root directory defining the filesystem boundary.
     * <p>定义文件系统边界的根目录。
     */
    private final Path root;
    /**
     * Quota.
     * <p>配额。
     */
    private final UploadQuota quota;
    /**
     * Minimum free disk space in bytes.
     * <p>最小磁盘剩余空间，单位为字节。
     */
    private final long minimumFreeBytes;

    /**
     * Validates and binds the inputs required by web workspace.
     * <p>校验并绑定Web工作区所需输入。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param quota quota / 配额
     * @param minimumFreeBytes minimum free disk space in bytes / 最小磁盘剩余空间，单位为字节
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public WebWorkspace(Path root, UploadQuota quota, long minimumFreeBytes) throws IOException {
        if (minimumFreeBytes < 0) throw new IllegalArgumentException("Invalid free-space reserve");
        this.root = root.toAbsolutePath().normalize(); this.quota = quota; this.minimumFreeBytes = minimumFreeBytes;
        safeAncestors(this.root); Files.createDirectories(this.root);
    }

    /**
     * Returns quota.
     * <p>返回配额。
     *
     * @return quota / 配额
     */
    public UploadQuota quota() { return quota; }
    /**
     * Returns minimum free disk space in bytes.
     * <p>返回最小磁盘剩余空间，单位为字节。
     *
     * @return minimum free disk space in bytes / 最小磁盘剩余空间，单位为字节
     */
    public long minimumFreeBytes() { return minimumFreeBytes; }

    /**
     * Lists validated resource addresses whose on-disk ownership belongs to the selected workspace.
     * <p>列出磁盘归属属于所选工作区的已校验资源地址。
     *
     * @param workspaceId server-assigned workspace identifier / 服务端分配的工作区标识
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public synchronized java.util.List<WorkspaceAddress> owned(String workspaceId) throws IOException {
        new WorkspaceAddress(workspaceId,"00000000-0000-0000-0000-000000000000");
        Path parent=root.resolve(workspaceId);safeAncestors(parent);
        if(!Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS))return java.util.List.of();
        var result=new java.util.ArrayList<WorkspaceAddress>();
        try(var children=Files.list(parent)) {
            for(Path child:children.toList()) {
                if(!child.getFileName().toString().matches("[a-f0-9-]{36}") || !Files.isDirectory(child,LinkOption.NOFOLLOW_LINKS))continue;
                var address=new WorkspaceAddress(workspaceId,child.getFileName().toString());
                try { directory(address);result.add(address); } catch(IOException unowned) { /* Preserve anything without exact ownership. / 保留任何无法确认精确归属的内容。 */ }
            }
        }
        return java.util.List.copyOf(result);
    }

    /**
     * Tests the exists predicate against the supplied evidence.
     * <p>根据所提供证据检查存在条件。
     *
     * @param address address / 地址
     * @return true when exists predicate against the supplied evidence, false otherwise / 根据所提供证据检查存在条件时为 true，否则为 false
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public boolean exists(WorkspaceAddress address) throws IOException {
        Path path=root.resolve(address.workspaceId()).resolve(address.resourceId());safeAncestors(path);
        return Files.exists(path,LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Checks capacity.
     * <p>检查容量。
     *
     * @param address address / 地址
     * @param additionalBytes additional bytes / 额外字节
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public synchronized void checkCapacity(WorkspaceAddress address, long additionalBytes) throws IOException {
        Path directory=directory(address);
        if(additionalBytes<0 || size(directory)+additionalBytes>quota.projectBytes() || size(root)+additionalBytes>quota.totalBytes()
                || Files.getFileStore(root).getUsableSpace()<additionalBytes+minimumFreeBytes) throw new IOException("Workspace capacity exceeded");
    }

    /**
     * Discards child.
     * <p>清理子项。
     *
     * @param address address / 地址
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public synchronized void discardChild(WorkspaceAddress address,String name) throws IOException {
        if(!name.matches("[a-z][a-z0-9.-]*")) throw new IOException("Invalid cleanup target");
        Path directory=directory(address), child=directory.resolve(name).normalize();
        if(!child.startsWith(directory) || child.equals(directory))throw new IOException("Cleanup boundary mismatch");
        if(!Files.exists(child,LinkOption.NOFOLLOW_LINKS))return;
        try(var paths=Files.walk(child)) {
            for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) {
                if(!path.startsWith(child))throw new IOException("Cleanup boundary mismatch");
                if(!Files.isSymbolicLink(path) && Files.getFileStore(path).supportsFileAttributeView("dos")) Files.setAttribute(path,"dos:readonly",false,LinkOption.NOFOLLOW_LINKS);
                Files.delete(path);
            }
        }
    }

    /**
     * Creates path.
     * <p>创建路径。
     *
     * @param address address / 地址
     * @return path / 路径
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public synchronized Path create(WorkspaceAddress address) throws IOException {
        Path directory = root.resolve(address.workspaceId()).resolve(address.resourceId());
        safeAncestors(directory); Files.createDirectories(directory.getParent());
        Files.createDirectory(directory);
        Files.writeString(directory.resolve(".ownership"), address.marker(), StandardOpenOption.CREATE_NEW);
        Files.createDirectory(directory.resolve("source"));
        return directory;
    }

    /**
     * Resolves the resource directory only after validating safe ancestors and its exact ownership marker.
     * <p>仅在验证安全祖先路径及精确归属标记后解析资源目录。
     *
     * @param address address / 地址
     * @return the resource directory only after validating safe ancestors and its exact ownership marker / 仅在验证安全祖先路径及精确归属标记后解析资源目录
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public Path directory(WorkspaceAddress address) throws IOException {
        Path directory = root.resolve(address.workspaceId()).resolve(address.resourceId());
        safeAncestors(directory);
        Path marker = directory.resolve(".ownership");
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) > 250
                || !Files.readString(marker).equals(address.marker())) throw new IOException("Workspace ownership mismatch");
        return directory;
    }

    /**
     * Returns the source subdirectory of a verified owned workspace directory.
     * <p>返回已验证自有工作区目录的源码子目录。
     *
     * @param address address / 地址
     * @return the source subdirectory of a verified owned workspace directory / 已验证自有工作区目录的源码子目录
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public Path source(WorkspaceAddress address) throws IOException { return directory(address).resolve("source"); }

    /**
     * Streams an upload into verified owned storage while enforcing file and aggregate workspace quotas.
     * <p>将上传流式写入已验证自有存储，同时执行文件及工作区总量配额。
     *
     * @param address address / 地址
     * @param relative relative / 相对
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return upload as a numeric result / 上传的数值结果
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public synchronized long upload(WorkspaceAddress address, String relative, InputStream input) throws IOException {
        Path directory = directory(address);
        if (Files.exists(directory.resolve(".complete"), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Source already finalized");
        Path destination = safeMember(directory.resolve("source"), relative);
        long used = size(directory);
        long total = size(root);
        try (var paths = Files.walk(directory.resolve("source"))) {
            if (paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).limit(quota.members()).count() >= quota.members())
                throw new IOException("Too many source members");
        }
        Files.createDirectories(destination.getParent());
        safeAncestors(destination.getParent());
        long count = 0;
        boolean created = false;
        try {
            try (var output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                created = true;
                byte[] buffer = new byte[32768];
                for (int read; (read = input.read(buffer)) != -1;) {
                    count += read;
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Upload cancelled");
                    if (count > quota.fileBytes() || used + count > quota.projectBytes() || total + count > quota.totalBytes())
                        throw new IOException("Upload quota exceeded");
                    output.write(buffer, 0, read);
                }
            }
            return count;
        } catch (IOException failure) {
            if (created) Files.deleteIfExists(destination);
            throw failure;
        }
    }

    /**
     * Marks nonempty uploaded source ready by creating the completion marker exactly once.
     * <p>通过一次性创建完成标记，将非空已上传源码标为就绪。
     *
     * @param address address / 地址
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public synchronized void complete(WorkspaceAddress address) throws IOException {
        Path directory = directory(address);
        if (size(directory.resolve("source")) == 0) throw new IOException("Source has no content");
        Files.writeString(directory.resolve(".complete"), "ready", StandardOpenOption.CREATE_NEW);
    }

    /**
     * Discards web workspace.
     * <p>清理Web工作区。
     *
     * @param address address / 地址
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public synchronized void discard(WorkspaceAddress address) throws IOException {
        Path directory = directory(address);
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.normalize().startsWith(root) || path.equals(root)) throw new IOException("Cleanup boundary mismatch");
                if (Files.isSymbolicLink(path)) throw new IOException("Workspace contains a link");
                Files.delete(path);
            }
        }
    }

    /**
     * Validates and produces safe member for the next contract boundary.
     * <p>校验并生成供下一契约边界使用的安全成员。
     *
     * @param base base / 基础
     * @param relative relative / 相对
     * @return constructed or resolved path / 构造或解析得到的路径
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public Path safeMember(Path base, String relative) throws IOException {
        if (relative == null || relative.isBlank() || relative.length() > quota.pathLength() || relative.startsWith("/")
                || relative.contains("\\") || relative.contains(":") || relative.contains("\0")) throw new IOException("Invalid source member");
        for (String part : relative.split("/", -1)) {
            String upper = part.toUpperCase(Locale.ROOT);
            if (part.isBlank() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")
                    || part.chars().anyMatch(ch -> ch < 32) || part.matches(".*[<>\"|?*].*")) throw new IOException("Invalid source member");
            if (upper.matches("(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])(\\..*)?")) throw new IOException("Reserved source member");
        }
        Path path = base.resolve(relative).normalize();
        if (!path.startsWith(base.normalize()) || path.equals(base)) throw new IOException("Source boundary escape");
        safeAncestors(path);
        return path;
    }

    /**
     * Validates and produces safe ancestors for the next contract boundary.
     * <p>校验并生成供下一契约边界使用的安全祖先集合。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public static void safeAncestors(Path path) throws IOException {
        for (Path current = path.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic workspace path");
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                var attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isOther()) throw new IOException("Special workspace path");
            }
        }
    }

    /**
     * Totals regular file bytes under the directory and rejects symbolic links or special files.
     * <p>累计目录下常规文件字节数，并拒绝符号链接或特殊文件。
     *
     * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
     * @return total regular-file content size in bytes / 常规文件内容总大小，单位为字节
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public static long size(Path directory) throws IOException {
        long size = 0;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.toList()) {
                var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("Unsafe workspace member");
                if (attributes.isRegularFile()) size = Math.addExact(size, attributes.size());
            }
        }
        return size;
    }
}
