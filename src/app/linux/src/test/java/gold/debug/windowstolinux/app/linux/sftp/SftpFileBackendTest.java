package gold.debug.windowstolinux.app.linux.sftp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import gold.debug.windowstolinux.app.file.*;
import gold.debug.windowstolinux.app.file.FileEngine;
import gold.debug.windowstolinux.app.file.model.FileActionLog;
import gold.debug.windowstolinux.app.linux.ssh.connect.*;
import gold.debug.windowstolinux.app.linux.ssh.connect.SshConnector;
import gold.debug.windowstolinux.app.windows.file.WindowsLinks;
import gold.debug.windowstolinux.appapi.file.action.DeleteEffect;
import gold.debug.windowstolinux.appapi.file.action.FileChoice;
import gold.debug.windowstolinux.appapi.file.model.FileInfo;
import gold.debug.windowstolinux.appapi.file.model.FilePath;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.apache.sshd.common.file.root.RootedFileSystem;
import org.apache.sshd.sftp.server.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SftpFileBackendTest {
    @TempDir
    Path root;

    final ServerId server = new ServerId("fixture");

    FilePath remote(String p) {
        return FilePath.remote(server, p);
    }

    final FileEngine.Decisions decisions = new FileEngine.Decisions() {
        public FileChoice.Directory directory(FileInfo a, FileInfo b) {
            return FileChoice.Directory.MERGE;
        }

        public FileChoice.Conflict file(FileInfo a, FileInfo b) {
            return FileChoice.Conflict.OVERWRITE;
        }
    };

    @Test
    void actualSftpVersionsCopyRecursiveContentAndKeepTwoLayerConflictResults() throws Exception {
        for (int version : new int[]{3, 6}) {
            Path directory = Files.createDirectories(root.resolve("v" + version));
            try (var fixture = new SftpTransferTest.Fixture(directory, version, new SftpEventListener() {
            });
                 var connector = new SshConnector(); var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
                Files.createDirectories(fixture.remote.resolve("from/sub"));
                Files.createDirectories(fixture.remote.resolve("to"));
                Files.writeString(fixture.remote.resolve("from/a"), "incoming");
                Files.writeString(fixture.remote.resolve("from/sub/b"), "independent");
                Files.writeString(fixture.remote.resolve("to/a"), "existing");
                Files.writeString(fixture.remote.resolve("to/unique"), "keep");
                connection.withSftp(() -> false, client -> {
                    var engine = new FileEngine(new SftpFileBackend(client, server));
                    var journal = new ActionLog();
                    var copied = engine.copy(remote("/from"), remote("/to"), false, false, FileChoice.of(FileChoice.Directory.MERGE, FileChoice.Conflict.SKIP), engine, decisions, (a, b, c) -> {
                    }, journal, () -> false, (a, b) -> {
                    });
                    assertEquals(TaskResult.FAILED, copied.outcome());
                    assertEquals("existing", Files.readString(fixture.remote.resolve("to/a")));
                    assertEquals("independent", Files.readString(fixture.remote.resolve("to/sub/b")));
                    assertEquals("keep", Files.readString(fixture.remote.resolve("to/unique")));
                    assertArrayEquals("independent".getBytes(), engine.read(remote("/to/sub/b"), 32, () -> false));
                    assertTrue(journal.phases.contains("BEFORE_WRITE"));
                    return null;
                });
            }
        }
    }

    @Test
    void actualWireLinkSemanticsPreserveInternalAndBrokenTargetsWithoutFollowingDelete() throws Exception {
        var links = new LinuxLinks();
        try (var fixture = new SftpTransferTest.Fixture(root, 6, new SftpEventListener() {
        }, links);
             var connector = new SshConnector(); var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
            Files.createDirectories(fixture.remote.resolve("from/inside"));
            Files.createDirectories(fixture.remote.resolve("outside"));
            Files.createDirectories(fixture.remote.resolve("to"));
            Files.writeString(fixture.remote.resolve("from/inside/content"), "inside");
            Files.writeString(fixture.remote.resolve("outside/content"), "outside");
            connection.withSftp(() -> false, client -> {
                var backend = new SftpFileBackend(client, server);
                var engine = new FileEngine(backend);
                var journal = new ActionLog();
                backend.link(remote("/from/internal"), "inside");
                backend.link(remote("/from/external"), "/outside");
                backend.link(remote("/from/broken"), "missing");
                var report = engine.copy(remote("/from"), remote("/to"), false, false, FileChoice.of(FileChoice.Directory.COPY, FileChoice.Conflict.COPY), engine, decisions, (a, b, c) -> {
                }, journal, () -> false, (a, b) -> {
                });
                assertEquals(TaskResult.SUCCEEDED, report.outcome(), report.toString());
                assertEquals("/to (1)/inside", engine.inspect(remote("/to (1)/internal"), false, () -> false).actual().path());
                assertEquals("/outside", engine.inspect(remote("/to (1)/external"), false, () -> false).actual().path());
                assertEquals("/from/missing", FileEngine.resolveTarget(remote("/to (1)/broken"), engine.inspect(remote("/to (1)/broken"), true, () -> false).target().orElseThrow()).path());
                assertThrows(FileNotFoundException.class, () -> engine.inspect(remote("/to (1)/broken"), false, () -> false));
                assertEquals(TaskResult.SUCCEEDED, engine.delete(engine.inspect(remote("/to (1)"), false, () -> false), false, DeleteEffect.PERMANENT, journal, () -> false).outcome());
                assertEquals("outside", Files.readString(fixture.remote.resolve("outside/content")));
                return null;
            });
        } finally {
            links.close();
        }
    }

    @Test
    void providerRefreshUsesTheNewOwnedChannelAfterOriginalScopeCloses() throws Exception {
        try (var fixture = new SftpTransferTest.Fixture(root, 6, new SftpEventListener() {
        }); var connector = new SshConnector()) {
            Files.writeString(fixture.remote.resolve("content"), "data");
            var first = SshCommandExecutionTest.connect(connector, fixture.server);
            var scope = first.openFileScope(() -> false);
            var current = new java.util.concurrent.atomic.AtomicReference<>(scope.channel());
            var backend = new SftpFileBackend(server, current::get);
            assertThrows(IllegalArgumentException.class, () -> backend.stat(FilePath.remote(new ServerId("another-host"), "/content")));
            assertEquals(4, backend.stat(remote("/content")).orElseThrow().bytes());
            scope.close();
            first.close();
            try (var second = SshCommandExecutionTest.connect(connector, fixture.server); var fresh = second.openFileScope(() -> false)) {
                current.set(fresh.channel());
                assertArrayEquals("data".getBytes(), new FileEngine(backend).read(remote("/content"), 8, () -> false));
            }
        }
    }

    @Test
    void actualWireLinkSelfSurvivesInaccessibleTargetsAndFileEntrypointsDistinguishMissingTargets() throws Exception {
        var links = new LinuxLinks();
        try (var fixture = new SftpTransferTest.Fixture(root, 6, new SftpEventListener() {
        }, links);
             var connector = new SshConnector(); var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
            Files.writeString(fixture.remote.resolve("inaccessible"), "private original");
            links.denied.add(fixture.remote.resolve("inaccessible"));
            connection.withSftp(() -> false, client -> {
                var backend = new SftpFileBackend(client, server);
                var engine = new FileEngine(backend);
                var journal = new ActionLog();
                backend.link(remote("/private-link"), "/inaccessible");
                backend.link(remote("/missing-link"), "/missing");
                IOException denied = assertThrows(IOException.class, () -> engine.inspect(remote("/private-link"), false, () -> false));
                assertFalse(denied instanceof FileNotFoundException);
                assertThrows(FileNotFoundException.class, () -> engine.inspect(remote("/missing-link"), false, () -> false));
                for (String source : List.of("private-link", "missing-link")) {
                    FileInfo original = engine.inspect(remote("/" + source), true, () -> false);
                    assertTrue(engine.optionalTarget(original, () -> false).isEmpty());
                    var report = engine.copy(original.actual(), remote("/" + source + "-copy"), true, false, FileChoice.ask(), engine, decisions, (a, b, c) -> {
                    }, journal, () -> false, (a, b) -> {
                    });
                    assertEquals(TaskResult.SUCCEEDED, report.outcome(), report.toString());
                    assertEquals(original.target(), engine.inspect(remote("/" + source + "-copy"), true, () -> false).target());
                    assertFalse(journal.targets.containsKey(remote("/" + source + "-copy")));
                }
                assertEquals("private original", Files.readString(fixture.remote.resolve("inaccessible")));
                return null;
            });
        } finally {
            links.close();
        }
    }

    @Test
    void unreadableSourceChildFailsWithoutPreventingIndependentDirectoryCopies() throws Exception {
        var links = new LinuxLinks();
        try (var fixture = new SftpTransferTest.Fixture(root, 6, new SftpEventListener() {
        }, links);
             var connector = new SshConnector(); var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
            Files.createDirectory(fixture.remote.resolve("from"));
            Files.writeString(fixture.remote.resolve("from/denied"), "private bytes");
            Files.writeString(fixture.remote.resolve("from/independent"), "independent bytes");
            links.unreadable.add(fixture.remote.resolve("from/denied"));
            connection.withSftp(() -> false, client -> {
                var engine = new FileEngine(new SftpFileBackend(client, server));
                var journal = new ActionLog();
                var report = engine.copy(remote("/from"), remote("/copied"), false, false, FileChoice.ask(), engine, decisions,
                    (a, b, c) -> {
                    }, journal, () -> false, (a, b) -> {
                    });
                assertEquals(TaskResult.FAILED, report.outcome(), report.toString());
                assertTrue(report.items().stream().anyMatch(item -> item.source().equals(remote("/from/denied")) && item.outcome() == TaskResult.FAILED));
                assertTrue(report.items().stream().anyMatch(item -> item.source().equals(remote("/from/independent")) && item.outcome() == TaskResult.SUCCEEDED));
                assertFalse(Files.exists(fixture.remote.resolve("copied/denied")));
                assertEquals("independent bytes", Files.readString(fixture.remote.resolve("copied/independent")));
                assertEquals("private bytes", Files.readString(fixture.remote.resolve("from/denied")));
                try (var entries = Files.list(fixture.remote.resolve("copied"))) {
                    assertEquals(List.of("independent"), entries.map(path -> path.getFileName().toString()).toList());
                }
                return null;
            });
        } finally {
            links.close();
        }
    }

    // This Windows-hosted SFTP fixture models Linux symlink wire attributes using owned placeholders.
    // Real native junction behavior is tested in FileEngineTest; this is a controlled protocol test.
    static final class LinuxLinks implements SftpFileSystemAccessor, AutoCloseable {
        final Map<Path, String> links = new ConcurrentHashMap<>();

        final Set<Path> denied = ConcurrentHashMap.newKeySet();

        final Set<Path> unreadable = ConcurrentHashMap.newKeySet();

        @Override
        public java.nio.channels.SeekableByteChannel openFile(SftpSubsystemProxy subsystem, FileHandle handle, Path path,
                                                              String handleId, Set<? extends OpenOption> options, java.nio.file.attribute.FileAttribute<?>... attributes) throws IOException {
            if (unreadable.contains(nativePath(path))) throw new AccessDeniedException(path.toString());
            return SftpFileSystemAccessor.super.openFile(subsystem, handle, path, handleId, options, attributes);
        }

        Path nativePath(Path path) {
            var fs = (RootedFileSystem) path.getFileSystem();
            Path result = fs.getRoot().resolve(path.toAbsolutePath().toString().substring(1)).normalize();
            assertTrue(result.startsWith(fs.getRoot()));
            return result;
        }

        @Override
        public void createLink(SftpSubsystemProxy s, Path link, Path target, boolean symbolic) throws IOException {
            assertTrue(symbolic);
            String text = target.toString();
            Path destination = nativePath(link), resolved = text.startsWith("/") ? ((RootedFileSystem) link.getFileSystem()).getRoot().resolve(text.substring(1)) : destination.getParent().resolve(text);
            Files.createFile(destination);
            links.put(destination, text);
        }

        @Override
        public String resolveLinkTarget(SftpSubsystemProxy s, Path link) throws IOException {
            String text = links.get(nativePath(link));
            if (text == null) throw new IOException("Not a link");
            return text;
        }

        @Override
        public Map<String, ?> readFileAttributes(SftpSubsystemProxy subsystem, Path path, String view, LinkOption... options) throws IOException {
            if (denied.contains(nativePath(path))) throw new AccessDeniedException(path.toString());
            return SftpFileSystemAccessor.super.readFileAttributes(subsystem, path, view, options);
        }

        @Override
        public NavigableMap<String, Object> resolveReportedFileAttributes(SftpSubsystemProxy s, Path p, int flags, NavigableMap<String, Object> a, LinkOption... options) throws IOException {
            if (denied.contains(nativePath(p))) throw new AccessDeniedException(p.toString());
            if (links.containsKey(nativePath(p))) {
                a.put("isSymbolicLink", true);
                a.put("isDirectory", false);
                a.put("isRegularFile", false);
            }
            return a;
        }

        @Override
        public void renameFile(SftpSubsystemProxy s, Path old, Path target, Collection<CopyOption> options) throws IOException {
            SftpFileSystemAccessor.super.renameFile(s, old, target, options);
            String text = links.remove(nativePath(old));
            if (text != null) links.put(nativePath(target), text);
        }

        @Override
        public void removeFile(SftpSubsystemProxy s, Path p, boolean directory) throws IOException {
            SftpFileSystemAccessor.super.removeFile(s, p, directory);
            links.remove(nativePath(p));
        }

        public void close() throws IOException {
            for (Path p : links.keySet()) Files.deleteIfExists(p);
            links.clear();
        }
    }

    static final class ActionLog implements FileActionLog {
        final List<String> phases = new ArrayList<>();

        final Map<FilePath, FileInfo> links = new HashMap<>();

        final Map<FilePath, String> targets = new HashMap<>();

        public UUID begin(String t, FilePath s, Optional<FilePath> d, String p) {
            phases.add("PLANNED");
            return UUID.randomUUID();
        }

        public void record(UUID o, String p, FilePath s, Optional<FilePath> t, String d) {
            phases.add(p);
        }

        public List<FileInfo> links(FilePath p) {
            return List.copyOf(links.values());
        }

        public boolean targetMatches(FileInfo l, FileInfo t) {
            return t.identity().equals(targets.get(l.actual()));
        }

        public void saveLink(FileInfo l, Optional<FileInfo> t) {
            links.put(l.actual(), l);
            t.ifPresent(a -> targets.put(l.actual(), a.identity()));
        }

        public void removeLink(FilePath p) {
            links.remove(p);
            targets.remove(p);
        }
    }
}
