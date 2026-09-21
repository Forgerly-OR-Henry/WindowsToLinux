package gold.debug.windowstolinux.web.secret.masterkey;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.SecureRandom;
import java.util.*;

/**
 * Keeps owner-only keys outside the data directory and a non-secret identity across relocation and cold restore.
 * <p>将仅所有者可访问的密钥保存在数据目录外，并在迁移及冷恢复时保留非秘密身份。
 */
public final class WebMasterKeyStore {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private WebMasterKeyStore() { }

    /**
     * Loads the exact owner-protected master key or creates it for a new installation, checking the persisted identity before use.
     * <p>加载精确的仅所有者可访问主密钥，或为新安装创建密钥，并在使用前检查持久化身份。
     *
     * @param dataDirectory data directory / 数据目录
     * @param keyDirectory separate directory for the owner-only key and lock files / 独立存放仅所有者可访问密钥与锁文件的目录
     * @return a new 32-byte array containing the master key; the caller must clear it after use / 包含主密钥的新建 32 字节数组，调用方应在使用后清零
     * @throws IOException if storage boundaries, ownership, permissions, key identity or key length are invalid, or storage access fails / 存储边界、所有权、权限、密钥身份或长度无效，或存储访问失败时
     */
    public static synchronized byte[] loadOrCreate(Path dataDirectory, Path keyDirectory) throws IOException {
        Path data = dataDirectory.toAbsolutePath().normalize();
        Path directory = keyDirectory.toAbsolutePath().normalize();
        if (directory.startsWith(data) || data.startsWith(directory)) throw new IOException("Key and data locations must be separate");
        safeAncestors(directory);
        UserPrincipal owner = FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        createDirectory(directory, owner);
        String identity = identity(data);
        Path lockFile = directory.resolve(identity + ".lock");
        try (var channel = openLock(lockFile, owner); var lock = channel.lock()) {
            Path file = directory.resolve(identity + ".key");
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.exists(data.resolve(gold.debug.windowstolinux.web.db.runtime.WebStorageLocation.DATABASE_NAME), LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Existing Web database requires its original master key");
                createKey(file, owner);
            }
            verifyPrivate(file, owner, false);
            byte[] bytes;
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(33); }
            if (bytes.length != 32) {
                Arrays.fill(bytes, (byte) 0); throw new IOException("Invalid saved Web master key");
            }
            return bytes;
        }
    }

    /**
     * Creates directory within the caller's controlled storage boundary.
     * <p>创建调用方受控存储边界内的目录。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @param owner operating-system account that must own the directory / 必须拥有该目录的操作系统账户
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static void createDirectory(Path path, UserPrincipal owner) throws IOException {
        safeAncestors(path);
        if (!Files.exists(path.getParent(), LinkOption.NOFOLLOW_LINKS)) createDirectory(path.getParent(), owner);
        try { Files.createDirectory(path, permissions(path.getParent(), owner, true)); }
        catch (FileAlreadyExistsException existing) { /* Reuse only after ownership and permission checks below. / 仅在下方归属及权限检查通过后复用。 */ }
        verifyPrivate(path, owner, true);
    }

    /**
     * Creates or verifies an owner-only lock file and opens it for writing without following links.
     * <p>创建或验证仅所有者可访问的锁文件，并以不跟随链接的方式打开写通道。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @param owner operating-system account that must own the lock file / 必须拥有锁文件的操作系统账户
     * @return an open channel that the caller must lock and close / 调用方必须加锁并关闭的已打开通道
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static FileChannel openLock(Path path, UserPrincipal owner) throws IOException {
        try { Files.createFile(path, permissions(path.getParent(), owner, false)); }
        catch (FileAlreadyExistsException existing) { /* Existing lock files must also remain owner-only. / 既有锁文件也必须保持仅所有者可访问。 */ }
        verifyPrivate(path, owner, false);
        return FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Writes a new random 32-byte master key with owner-only permissions and clears the temporary byte array.
     * <p>以仅所有者可访问的权限写入新的随机 32 字节主密钥，并清零临时字节数组。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @param owner operating-system account granted exclusive access to the key / 获准独占访问密钥的操作系统账户
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static void createKey(Path path, UserPrincipal owner) throws IOException {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        try (var channel = FileChannel.open(path, Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS),
                permissions(path.getParent(), owner, false))) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        } finally { Arrays.fill(bytes, (byte) 0); }
    }

    /**
     * Builds owner-only filesystem permissions using POSIX attributes or the supported Windows ACL model.
     * <p>使用 POSIX 属性或受支持 Windows ACL 模型构建仅所有者可访问的文件系统权限。
     *
     * @param parent existing parent used to detect filesystem permission support / 用于检测文件系统权限支持的既有父目录
     * @param owner operating-system account granted exclusive access / 获准独占访问的操作系统账户
     * @param directory whether the target is a directory requiring owner execute permission on POSIX / 目标是否为在 POSIX 上需要所有者执行权限的目录
     * @return owner-only filesystem permissions using POSIX attributes or the supported Windows ACL model / 使用 POSIX 属性或受支持 Windows ACL 模型构建仅所有者可访问的文件系统权限
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static FileAttribute<?> permissions(Path parent, UserPrincipal owner, boolean directory) throws IOException {
        if (Files.getFileStore(parent).supportsFileAttributeView("posix"))
            return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        if (Files.getFileStore(parent).supportsFileAttributeView("acl")) {
            var entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
            return new FileAttribute<List<AclEntry>>() {
                /**
                 * Names the ACL attribute applied atomically at file creation.
                 * <p>返回创建文件时原子应用的 ACL 属性名称。
                 *
                 * @return the standard {@code acl:acl} attribute name / 标准 {@code acl:acl} 属性名称
                 */
                public String name() { return "acl:acl"; }
                /**
                 * Returns the single ACL entry granting access only to the selected owner.
                 * <p>返回仅向指定所有者授予访问权限的单条 ACL 记录。
                 *
                 * @return an immutable list containing the owner-only ACL entry / 包含仅所有者可访问 ACL 记录的不可变列表
                 */
                public List<AclEntry> value() { return List.of(entry); }
            };
        }
        throw new IOException("Owner-only Web key storage is unavailable on this filesystem");
    }

    /**
     * Rejects linked paths, unexpected file types, ownership mismatches and permissions allowing other users.
     * <p>拒绝链接路径、非预期文件类型、所有权不符及允许其他用户访问的权限。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @param owner required operating-system owner of the path / 路径必须归属的操作系统账户
     * @param directory whether a directory is required instead of a regular file / 是否要求目标为目录而非普通文件
     * @throws IOException if the path or its access controls are unsafe, or attributes cannot be read / 路径或访问控制不安全，或无法读取属性时
     */
    private static void verifyPrivate(Path path, UserPrincipal owner, boolean directory) throws IOException {
        safeAncestors(path);
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (directory ? !attributes.isDirectory() : !attributes.isRegularFile()) throw new IOException("Invalid Web key storage path");
        if (!Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(owner)) throw new IOException("Web key storage ownership mismatch");
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            if (!posix.readAttributes().permissions().equals(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------")))
                throw new IOException("Web key storage permissions must be owner-only");
            return;
        }
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null || acl.getAcl().isEmpty() || acl.getAcl().stream().anyMatch(entry ->
                entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner)))
            throw new IOException("Web key storage permissions must be owner-only");
    }

    /**
     * Checks each existing ancestor, including the path itself, and rejects symbolic links or special files.
     * <p>检查包含当前路径在内的各级既有祖先路径，拒绝符号链接及特殊文件。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static void safeAncestors(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                var attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("Linked or special Web key path rejected");
            }
        }
    }

    /**
     * Loads the persisted master-key identity, creating it only for a new data directory without an existing database.
     * <p>加载持久化主密钥身份，仅为没有既有数据库的新数据目录创建身份。
     *
     * @param data existing data directory containing the identity and database / 存放身份文件和数据库的既有数据目录
     * @return the validated canonical UUID selecting this installation's key file / 经验证的规范 UUID，用于选择当前安装的密钥文件
     * @throws IOException if an existing database lacks its identity, the identity is malformed, or file access fails / 既有数据库缺少身份、身份格式无效，或文件访问失败时
     */
    private static String identity(Path data) throws IOException {
        Path file = data.resolve(".master-key-id"); safeAncestors(file);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(data.resolve(gold.debug.windowstolinux.web.db.runtime.WebStorageLocation.DATABASE_NAME), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Existing Web database requires its original master key identity");
            Files.writeString(file, UUID.randomUUID().toString(), StandardOpenOption.CREATE_NEW);
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != 36)
            throw new IOException("Invalid Web master key identity");
        String value = Files.readString(file);
        try { if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid Web master key identity"); }
        return value;
    }
}
