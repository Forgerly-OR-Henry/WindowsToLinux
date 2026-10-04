package gold.debug.windowstolinux.app.linux.sftp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import gold.debug.windowstolinux.app.file.FileEngine;
import gold.debug.windowstolinux.app.file.model.FileRecycleReceipt;
import gold.debug.windowstolinux.app.linux.ssh.connect.*;
import gold.debug.windowstolinux.app.linux.ssh.connect.SshConnector;
import gold.debug.windowstolinux.appapi.file.action.DeleteEffect;
import gold.debug.windowstolinux.appapi.file.model.FilePath;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.apache.sshd.sftp.server.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises real SFTP packets with controlled Linux ownership and mode attributes on Windows. */
class SftpTrashTest {
    @TempDir
    Path root;

    final ServerId server = new ServerId("fixture");

    FilePath remote(String path) {
        return FilePath.remote(server, path);
    }

    @Test
    void actualSftpThreeAndSixRecycleAndRestoreFileNonemptyDirectoryAndLink() throws Exception {
        for (int version : new int[]{3, 6}) {
            Path directory = Files.createDirectories(root.resolve("v" + version));
            var files = new LinuxTrashFiles();
            try (var fixture = new SftpTransferTest.Fixture(directory, version, new SftpEventListener() {
            }, files);
                 var connector = new SshConnector(); var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
                Files.createDirectories(fixture.remote.resolve("home/fixture"));
                Files.createDirectories(fixture.remote.resolve("outside"));
                Files.writeString(fixture.remote.resolve("outside/keep"), "outside");
                connection.withSftp(() -> false, client -> {
                    assertEquals("/home/fixture", client.canonicalPath("."));
                    var backend = new SftpFileBackend(client, server);
                    var engine = new FileEngine(backend);
                    FilePath file = remote("/home/fixture/中文 + file.txt");
                    try (var output = backend.create(file)) {
                        output.write("restorable bytes".getBytes(StandardCharsets.UTF_8));
                    }
                    FilePath folder = remote("/home/fixture/folder");
                    backend.mkdir(folder);
                    try (var output = backend.create(folder.child("nested"))) {
                        output.write("directory bytes".getBytes(StandardCharsets.UTF_8));
                    }
                    FilePath link = remote("/home/fixture/link");
                    backend.link(link, "/outside");
                    for (FilePath path : List.of(file, folder, link)) {
                        assertTrue(backend.canRecycle(path));
                        var report = engine.delete(engine.inspect(path, path.equals(link), () -> false), path.equals(link), DeleteEffect.RECYCLE, new SftpFileBackendTest.ActionLog(), () -> false);
                        assertEquals(TaskResult.SUCCEEDED, report.outcome(), report.toString());
                        assertTrue(backend.stat(path).isEmpty());
                        Receipt receipt = receipt(backend);
                        var recovery = new FileRecycleReceipt(receipt.recycled(), backend.stat(receipt.recycled()).orElseThrow().identity(),
                            Optional.of(receipt.information()), Optional.of(backend.stat(receipt.information()).orElseThrow().identity()));
                        assertTrue(backend.recoveryInformationMatches(path, recovery));
                        assertTrue(engine.recoveryInformationMatches(path, recovery));
                        assertFalse(backend.recoveryInformationMatches(remote("/home/fixture/other"), recovery));
                        String metadata = new String(engine.read(receipt.information(), 8192, () -> false), StandardCharsets.UTF_8);
                        String encoded = metadata.lines().filter(line -> line.startsWith("Path=")).findFirst().orElseThrow().substring(5);
                        assertEquals(path.path(), URLDecoder.decode(encoded, StandardCharsets.UTF_8));
                        assertTrue(metadata.startsWith("[Trash Info]\n"));
                        assertTrue(metadata.contains("\nDeletionDate="));
                        assertEquals(0600, client.lstat(receipt.information().path()).getPermissions() & 0777);
                        for (String part : List.of("", "/files", "/info"))
                            assertEquals(0700, client.lstat("/home/fixture/.local/share/Trash" + part).getPermissions() & 0777);
                        backend.publish(receipt.recycled(), path, false);
                        backend.remove(receipt.information());
                        assertTrue(backend.stat(path).isPresent());
                    }
                    assertArrayEquals("restorable bytes".getBytes(StandardCharsets.UTF_8), engine.read(file, 64, () -> false));
                    assertArrayEquals("directory bytes".getBytes(StandardCharsets.UTF_8), engine.read(folder.child("nested"), 64, () -> false));
                    assertEquals("/outside", engine.inspect(link, true, () -> false).target().orElseThrow());
                    assertEquals("outside", Files.readString(fixture.remote.resolve("outside/keep")));
                    assertTrue(backend.list(remote("/home/fixture/.local/share/Trash/info"), 10).isEmpty());
                    return null;
                });
            } finally {
                files.close();
            }
        }
    }

    @Test
    void unsafeTrashOwnersModesMissingFieldsAndRedirectedAncestorsAreRejected() throws Exception {
        for (int version : new int[]{3, 6}) {
            var files = new LinuxTrashFiles();
            try (var fixture = new SftpTransferTest.Fixture(Files.createDirectories(root.resolve("unsafe" + version)), version, new SftpEventListener() {
            }, files);
                 var connector = new SshConnector(); var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
                Path home = Files.createDirectories(fixture.remote.resolve("home/fixture"));
                Path trash = Files.createDirectories(home.resolve(".local/share/Trash"));
                Files.createDirectories(trash.resolve("files"));
                Files.createDirectories(trash.resolve("info"));
                Files.writeString(home.resolve("keep"), "retained");
                connection.withSftp(() -> false, client -> {
                    var backend = new SftpFileBackend(client, server);
                    FilePath path = remote("/home/fixture/keep");
                    assertTrue(backend.canRecycle(path));
                    assertFalse(backend.canRecycle(remote("/")));
                    assertFalse(backend.canRecycle(remote("/outside")));
                    for (String forbidden : List.of("/home/fixture/.local", "/home/fixture/.local/share", "/home/fixture/.local/share/Trash", "/home/fixture/.local/share/Trash/files"))
                        assertFalse(backend.canRecycle(remote(forbidden)));
                    for (Path unsafe : List.of(trash, trash.resolve("files"), trash.resolve("info"))) {
                        files.modes.put(unsafe, 0755);
                        assertFalse(backend.canRecycle(path));
                        files.modes.remove(unsafe);
                        files.foreignOwners.add(unsafe);
                        assertFalse(backend.canRecycle(path));
                        files.foreignOwners.remove(unsafe);
                        files.missingOwners.add(unsafe);
                        assertFalse(backend.canRecycle(path));
                        files.missingOwners.remove(unsafe);
                    }
                    Path local = home.resolve(".local");
                    files.modes.put(local, 0777);
                    assertFalse(backend.canRecycle(path));
                    files.modes.remove(local);
                    files.links.links.put(local, "/outside");
                    assertFalse(backend.canRecycle(path));
                    files.links.links.remove(local);
                    files.missingOwners.add(home);
                    assertFalse(backend.canRecycle(path));
                    files.missingOwners.remove(home);
                    assertEquals("retained", Files.readString(home.resolve("keep")));
                    assertTrue(backend.list(remote("/home/fixture/.local/share/Trash/info"), 10).isEmpty());
                    return null;
                });
            } finally {
                files.close();
            }
        }
    }

    @Test
    void failedAndUnacknowledgedTrashRenameRetainRecoveryEvidenceWithoutPermanentFallback() throws Exception {
        for (boolean afterEffect : new boolean[]{false, true}) {
            var files = new LinuxTrashFiles();
            files.failRenameAfter = afterEffect;
            try (var fixture = new SftpTransferTest.Fixture(Files.createDirectories(root.resolve("failure" + afterEffect)), 6, new SftpEventListener() {
            }, files);
                 var connector = new SshConnector(); var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
                Path home = Files.createDirectories(fixture.remote.resolve("home/fixture"));
                Files.writeString(home.resolve("keep"), "original");
                connection.withSftp(() -> false, client -> {
                    var backend = new SftpFileBackend(client, server);
                    var engine = new FileEngine(backend);
                    FilePath path = remote("/home/fixture/keep");
                    var journal = new SftpFileBackendTest.ActionLog();
                    files.failRename.set(true);
                    var report = engine.delete(engine.inspect(path, false, () -> false), false, DeleteEffect.RECYCLE, journal, () -> false);
                    assertEquals(TaskResult.UNKNOWN, report.outcome());
                    assertTrue(journal.phases.contains("BEFORE_DELETE"));
                    assertTrue(journal.phases.contains("UNKNOWN"));
                    assertEquals(1, files.failedRenames);
                    var information = backend.list(remote("/home/fixture/.local/share/Trash/info"), 10);
                    assertEquals(1, information.size());
                    FilePath info = remote("/home/fixture/.local/share/Trash/info").child(information.getFirst());
                    assertTrue(engine.read(info, 8192, () -> false).length > 0);
                    if (afterEffect) {
                        Receipt receipt = receipt(backend);
                        assertTrue(backend.stat(path).isEmpty());
                        backend.publish(receipt.recycled(), path, false);
                    } else assertTrue(backend.list(remote("/home/fixture/.local/share/Trash/files"), 10).isEmpty());
                    assertEquals("original", Files.readString(home.resolve("keep")));
                    backend.remove(info);
                    return null;
                });
            } finally {
                files.close();
            }
        }
    }

    @Test
    void cancellationBeforeDeletionCreatesNoTrashAndOwnedScopeStopsItsObserver() throws Exception {
        var files = new LinuxTrashFiles();
        try (var fixture = new SftpTransferTest.Fixture(root, 6, new SftpEventListener() {
        }, files); var connector = new SshConnector(); var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
            Path home = Files.createDirectories(fixture.remote.resolve("home/fixture"));
            Files.writeString(home.resolve("keep"), "original");
            AtomicBoolean cancelled = new AtomicBoolean();
            var scope = connection.openFileScope(cancelled::get);
            var client = scope.channel();
            var engine = new FileEngine(new SftpFileBackend(client, server));
            var info = engine.inspect(remote("/home/fixture/keep"), false, () -> false);
            cancelled.set(true);
            assertThrows(java.util.concurrent.CancellationException.class, () -> engine.delete(info, false, DeleteEffect.RECYCLE, new SftpFileBackendTest.ActionLog(), cancelled::get));
            scope.close();
            assertFalse(client.isOpen());
            assertFalse(Files.exists(home.resolve(".local")));
            assertEquals("original", Files.readString(home.resolve("keep")));
        } finally {
            files.close();
        }
    }

    Receipt receipt(SftpFileBackend backend) throws IOException {
        FilePath information = remote("/home/fixture/.local/share/Trash/info");
        String name = backend.list(information, 10).getFirst();
        return new Receipt(information.child(name), remote("/home/fixture/.local/share/Trash/files").child(name.substring(0, name.length() - ".trashinfo".length())));
    }

    record Receipt(FilePath information, FilePath recycled) {
    }

    static final class LinuxTrashFiles implements SftpFileSystemAccessor, AutoCloseable {
        final SftpFileBackendTest.LinuxLinks links = new SftpFileBackendTest.LinuxLinks();

        final Map<Path, Integer> modes = new ConcurrentHashMap<>();

        final Set<Path> foreignOwners = ConcurrentHashMap.newKeySet(), missingOwners = ConcurrentHashMap.newKeySet();

        final AtomicBoolean failRename = new AtomicBoolean();

        boolean failRenameAfter;

        int failedRenames;

        @Override
        public Path resolveLocalFilePath(SftpSubsystemProxy subsystem, Path base, String remote) throws IOException {
            return SftpFileSystemAccessor.super.resolveLocalFilePath(subsystem, base, remote.equals(".") ? "/home/fixture" : remote);
        }

        @Override
        public NavigableMap<String, Object> resolveReportedFileAttributes(SftpSubsystemProxy subsystem, Path path, int flags, NavigableMap<String, Object> attributes, LinkOption... options) throws IOException {
            links.resolveReportedFileAttributes(subsystem, path, flags, attributes, options);
            Path nativePath = links.nativePath(path);
            attributes.put("permissions", permissions(modes.getOrDefault(nativePath, Boolean.TRUE.equals(attributes.get("isDirectory")) ? 0700 : 0600)));
            if (missingOwners.contains(nativePath)) {
                attributes.remove("uid");
                attributes.remove("gid");
                attributes.remove("owner");
                attributes.remove("group");
            } else {
                attributes.put("uid", foreignOwners.contains(nativePath) ? 2000 : 1000);
                attributes.put("gid", 1000);
                attributes.put("owner", foreignOwners.contains(nativePath) ? "foreign" : "fixture");
                attributes.put("group", "fixture");
            }
            return attributes;
        }

        @Override
        public void setFilePermissions(SftpSubsystemProxy subsystem, Path path, Set<PosixFilePermission> permissions, LinkOption... options) {
            int mode = 0;
            for (PosixFilePermission value : permissions) mode |= 1 << (8 - value.ordinal());
            modes.put(links.nativePath(path), mode);
        }

        @Override
        public void createLink(SftpSubsystemProxy subsystem, Path link, Path target, boolean symbolic) throws IOException {
            links.createLink(subsystem, link, target, symbolic);
        }

        @Override
        public String resolveLinkTarget(SftpSubsystemProxy subsystem, Path link) throws IOException {
            return links.resolveLinkTarget(subsystem, link);
        }

        @Override
        public void renameFile(SftpSubsystemProxy subsystem, Path from, Path to, Collection<CopyOption> options) throws IOException {
            boolean fail = failRename.compareAndSet(true, false);
            if (fail) {
                failedRenames++;
                if (!failRenameAfter) throw new IOException("Controlled cross-device rename refusal");
            }
            links.renameFile(subsystem, from, to, options);
            Integer mode = modes.remove(links.nativePath(from));
            if (mode != null) modes.put(links.nativePath(to), mode);
            if (fail) throw new IOException("Controlled acknowledgement loss after rename");
        }

        @Override
        public void removeFile(SftpSubsystemProxy subsystem, Path path, boolean directory) throws IOException {
            links.removeFile(subsystem, path, directory);
            modes.remove(links.nativePath(path));
        }

        static Set<PosixFilePermission> permissions(int mode) {
            var result = EnumSet.noneOf(PosixFilePermission.class);
            for (var permission : PosixFilePermission.values())
                if ((mode & (1 << (8 - permission.ordinal()))) != 0) result.add(permission);
            return result;
        }

        public void close() throws IOException {
            links.close();
        }
    }
}
