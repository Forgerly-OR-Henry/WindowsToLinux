package gold.debug.windowstolinux.app.db.init;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.AclFileAttributeView;
import java.security.MessageDigest;
import java.util.*;

import com.sun.nio.file.ExtendedOpenOption;

/** File ownership, durable receipts and an atomic publication without deleting the original first. */
final class ServerOrderFiles {
    static final String DATABASE = "windowstolinux.mv.db";

    static final String ACCESS = "db-access.bin";

    static final String RECEIPT = "receipt.properties";

    private ServerOrderFiles() {
    }

    static Path root(Path path) throws IOException {
        if (!path.isAbsolute() || path.toString().contains(";") || path.toString().indexOf('\0') >= 0)
            throw new IOException("An absolute ordinary local path is required");
        var normalized = path.normalize();
        if (!normalized.equals(normalized.toRealPath()) || Files.isSymbolicLink(normalized)
            || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Reparse points and non-directory roots are refused");
        return normalized;
    }

    static void regular(Path file) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("The original regular file pair is required");
    }

    static Path newBackup(Path dataRoot, Path requested) throws IOException {
        if (!requested.isAbsolute() || requested.toString().contains(";") || requested.toString().indexOf('\0') >= 0)
            throw new IOException("An absolute new private backup directory is required");
        var normalized = requested.normalize();
        if (normalized.getParent() == null || normalized.getFileName() == null)
            throw new IOException("A new child backup directory is required");
        var backup = root(normalized.getParent()).resolve(normalized.getFileName());
        if (backup.startsWith(dataRoot) || dataRoot.startsWith(backup)
            || backup.toString().toLowerCase(Locale.ROOT).contains(".ai-workspace"))
            throw new IOException("Choose a private backup outside the data root and AI evidence directories");
        if (!Files.getFileStore(dataRoot).equals(Files.getFileStore(backup.getParent())))
            throw new IOException("Backup and working copies must be on the original volume");
        Files.createDirectory(backup);
        return backup;
    }

    static Ownership own(Path root) throws IOException {
        var channel = FileChannel.open(root.resolve("windowstolinux.lock"), StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            var lock = channel.tryLock();
            if (lock == null)
                throw new IOException("Another application owns the database");
            return new Ownership(channel, lock);
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    static FileChannel exclusive(Path file, boolean allowAtomicReplacement) throws IOException {
        regular(file);
        var options = new HashSet<OpenOption>(List.of(StandardOpenOption.READ, StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS, ExtendedOpenOption.NOSHARE_READ, ExtendedOpenOption.NOSHARE_WRITE));
        if (!allowAtomicReplacement)
            options.add(ExtendedOpenOption.NOSHARE_DELETE);
        return FileChannel.open(file, options);
    }

    static String hash(Path file) throws Exception {
        regular(file);
        try (var channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            return hash(channel);
        }
    }

    static String hash(FileChannel channel) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        var buffer = ByteBuffer.allocate(65536);
        channel.position(0);
        while (channel.read(buffer) != -1) {
            buffer.flip();
            digest.update(buffer);
            buffer.clear();
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void copy(FileChannel source, Path target) throws IOException {
        try (var output = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS)) {
            source.position(0);
            var buffer = ByteBuffer.allocate(65536);
            while (source.read(buffer) != -1) {
                buffer.flip();
                while (buffer.hasRemaining())
                    output.write(buffer);
                buffer.clear();
            }
            output.force(true);
        }
    }

    static void copy(Path source, Path target) throws IOException {
        regular(source);
        try (var input = FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            copy(input, target);
        }
    }

    static void receipt(Path backup, Properties values) throws Exception {
        var temporary = Files.createTempFile(backup, "receipt-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE);
                 var output = java.nio.channels.Channels.newOutputStream(channel)) {
                values.store(output, "Server ordering maintenance; contains hashes and identities, never credentials");
                output.flush();
                channel.force(true);
            }
            Files.move(temporary, backup.resolve(RECEIPT), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static Properties readReceipt(Path dataRoot, Path backup) throws Exception {
        var path = backup.resolve(RECEIPT);
        regular(path);
        if (Files.size(path) > 16384)
            throw new IOException("Invalid maintenance receipt");
        var values = new Properties();
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            values.load(input);
        }
        if (!values.stringPropertyNames().equals(Set.of("format", "root", "generation", "oldDdl", "newDdl",
            "beforeHash", "accessHash", "afterHash", "state")) || !values.getProperty("format").equals("1")
            || !dataRoot.equals(Path.of(values.getProperty("root"))))
            throw new IOException("Receipt is not bound to this original database root");
        for (var key : List.of("beforeHash", "accessHash", "oldDdl", "newDdl"))
            if (!values.getProperty(key).matches("[0-9a-f]{64}"))
                throw new IOException("Invalid receipt digest");
        if (!Set.of("BACKED_UP", "VALIDATED", "PUBLISHED", "FAILED", "RESTORED").contains(values.getProperty("state"))
            || !values.getProperty("afterHash").matches("(?:[0-9a-f]{64})?"))
            throw new IOException("Invalid maintenance stage");
        if (!hash(backup.resolve(DATABASE)).equals(values.getProperty("beforeHash"))
            || !hash(backup.resolve(ACCESS)).equals(values.getProperty("accessHash")))
            throw new IOException("The original paired backup failed integrity verification");
        return values;
    }

    static void publish(Path dataRoot, Path staged, String expectedDatabase, String expectedAccess,
                        String expectedStaged) throws Exception {
        if (!Files.getFileStore(dataRoot).equals(Files.getFileStore(staged)))
            throw new IOException("Atomic replacement requires the original volume");
        preservePermissions(dataRoot.resolve(DATABASE), staged);
        try (var database = exclusive(dataRoot.resolve(DATABASE), true);
             var access = exclusive(dataRoot.resolve(ACCESS), false);
             var candidate = exclusive(staged, true)) {
            if (!hash(database).equals(expectedDatabase) || !hash(access).equals(expectedAccess)
                || !hash(candidate).equals(expectedStaged))
                throw new IOException("The original pair changed outside the instance lock");
            ServerOrderAtomicMove.replace(staged, dataRoot.resolve(DATABASE));
            if (!hash(candidate).equals(expectedStaged))
                throw new IOException("The published name does not reference the guarded validated image");
        }
    }

    private static void preservePermissions(Path original, Path staged) throws IOException {
        var source = Files.getFileAttributeView(original, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        var target = Files.getFileAttributeView(staged, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (source == null || target == null)
            throw new IOException("The original file owner and Windows ACL must be preservable");
        var owner = source.getOwner();
        var acl = source.getAcl();
        target.setAcl(acl);
        if (!owner.equals(target.getOwner()))
            target.setOwner(owner);
        if (!owner.equals(target.getOwner()) || !acl.equals(target.getAcl()))
            throw new IOException("The replacement could not retain the original owner and ACL");
    }

    record Ownership(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }
}
