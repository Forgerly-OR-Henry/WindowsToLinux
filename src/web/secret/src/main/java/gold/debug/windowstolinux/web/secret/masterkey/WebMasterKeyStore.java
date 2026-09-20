package gold.debug.windowstolinux.web.secret.masterkey;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.SecureRandom;
import java.util.*;

/** Owner-only keys outside data; a non-secret identity survives relocation and cold restore. */
public final class WebMasterKeyStore {
    private WebMasterKeyStore() { }

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

    private static void createDirectory(Path path, UserPrincipal owner) throws IOException {
        safeAncestors(path);
        if (!Files.exists(path.getParent(), LinkOption.NOFOLLOW_LINKS)) createDirectory(path.getParent(), owner);
        try { Files.createDirectory(path, permissions(path.getParent(), owner, true)); }
        catch (FileAlreadyExistsException existing) { /* Reuse only after ownership and permission checks below. */ }
        verifyPrivate(path, owner, true);
    }

    private static FileChannel openLock(Path path, UserPrincipal owner) throws IOException {
        try { Files.createFile(path, permissions(path.getParent(), owner, false)); }
        catch (FileAlreadyExistsException existing) { /* Existing lock files must also remain owner-only. */ }
        verifyPrivate(path, owner, false);
        return FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    private static void createKey(Path path, UserPrincipal owner) throws IOException {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        try (var channel = FileChannel.open(path, Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS),
                permissions(path.getParent(), owner, false))) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        } finally { Arrays.fill(bytes, (byte) 0); }
    }

    private static FileAttribute<?> permissions(Path parent, UserPrincipal owner, boolean directory) throws IOException {
        if (Files.getFileStore(parent).supportsFileAttributeView("posix"))
            return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        if (Files.getFileStore(parent).supportsFileAttributeView("acl")) {
            var entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
            return new FileAttribute<List<AclEntry>>() {
                public String name() { return "acl:acl"; }
                public List<AclEntry> value() { return List.of(entry); }
            };
        }
        throw new IOException("Owner-only Web key storage is unavailable on this filesystem");
    }

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

    private static void safeAncestors(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                var attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("Linked or special Web key path rejected");
            }
        }
    }

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
