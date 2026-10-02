package gold.debug.windowstolinux.app.file;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.*;

import gold.debug.windowstolinux.app.file.SelectedDirectory;
import gold.debug.windowstolinux.app.file.FrozenSourceWork;
import gold.debug.windowstolinux.appapi.file.SnapshotRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceBoundaryTest {
    @TempDir
    Path root;

    @Test
    void extendedWindowsPathsKeepNativeIdentityThroughFreezeAndCleanup() throws Exception {
        Path selected = Files.createDirectory(root.resolve("selected"));
        String name = "a".repeat(90) + "/" + "b".repeat(90) + "/" + "c".repeat(70);
        Path file = selected.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "long path");
        assertTrue(file.toString().length() > 260);
        var source = new SelectedDirectory(selected);
        assertEquals("long path", new String(source.read(name, 9, () -> false).content()));
        Path payload;
        try (var work = work()) {
            work.prepare();
            work.capture(source, new SnapshotRequest(List.of(name), 9), () -> false);
            payload = work.directory();
            assertEquals("long path", Files.readString(payload.resolve(name)));
            work.verify(() -> false);
        }
        assertFalse(Files.exists(payload));
        assertEquals("long path", Files.readString(file));
    }

    @Test
    void portablePathsAndRequestedReadBudgetsAreEnforcedWithoutDeploymentExclusions() throws Exception {
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve(".env"), "local-fixture");
        Files.writeString(root.resolve("target/中文.txt"), "内容");
        Files.createFile(root.resolve("empty"));
        var source = new SelectedDirectory(root);
        assertEquals("local-fixture", new String(source.read(".env", 13, () -> false).content()));
        assertArrayEquals("内容".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            source.read("target/中文.txt", 6, () -> false).content());
        assertEquals(0, source.read("empty", 0, () -> false).content().length);
        assertThrows(IOException.class, () -> source.read(".env", 2, () -> false));
        assertThrows(IllegalArgumentException.class, () -> source.read("empty", 8L * 1024 * 1024 + 1, () -> false));
        for (String path : List.of("", "/absolute", "C:/file", "../.env", "target/../.env", "target//x", "target\\x",
            "./.env", "name.", "name ", "CON.txt", "nul", "LPT1/x", "name:stream", "bad\u0000name", "bad\uD800"))
            assertThrows(IllegalArgumentException.class, () -> SelectedDirectory.relative(path), path);
        Files.write(root.resolve("large"), new byte[8 * 1024 * 1024]);
        assertEquals(8 * 1024 * 1024, source.read("large", 8L * 1024 * 1024, () -> false).content().length);
    }

    @Test
    void containedJunctionIsAllowedAndEscapingJunctionIsRejected() throws Exception {
        Path selected = Files.createDirectory(root.resolve("selected"));
        Path inside = Files.createDirectory(selected.resolve("inside"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.writeString(inside.resolve("file"), "inside");
        Files.writeString(outside.resolve("file"), "outside");
        Path safe = selected.resolve("alias"), escape = selected.resolve("escape");
        try {
            junction(safe, inside);
            junction(escape, outside);
            var source = new SelectedDirectory(selected);
            assertEquals("inside", new String(source.read("alias/file", 10, () -> false).content()));
            assertThrows(IOException.class, () -> source.read("escape/file", 10, () -> false));
        } finally {
            Files.deleteIfExists(safe);
            Files.deleteIfExists(escape);
        }
        assertEquals("outside", Files.readString(outside.resolve("file")));
    }

    static void junction(Path link, Path target) throws Exception {
        var process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
            .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(8, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(3, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void observedIdentityAndContentChangesStopCopyAndCancellationStopsPublication() throws Exception {
        Path file = Files.write(root.resolve("file"), new byte[200000]);
        var source = new SelectedDirectory(root);
        var observed = source.inspect("file");
        assertThrows(IOException.class, () -> source.transfer(observed, new OutputStream() {
            boolean changed;

            @Override
            public void write(int value) {
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                if (!changed) {
                    changed = true;
                    Files.setLastModifiedTime(file,
                        FileTime.fromMillis(observed.attributes().lastModifiedTime().toMillis() + 10000));
                }
            }
        }, 200000, () -> false));
        assertThrows(CancellationException.class, () -> source.read("file", 200000, () -> true));
        var current = source.inspect("file");
        Files.move(file, root.resolve("old-file"));
        Files.write(file, new byte[200000]);
        assertThrows(IOException.class, () -> source.check(current));
    }

    @Test
    void snapshotsAreExplicitStableIndependentReadOnlyAndRetainNoPayloadAfterClose() throws Exception {
        Path selected = Files.createDirectory(root.resolve("selected"));
        Files.writeString(selected.resolve(".env"), "fixture");
        Files.createDirectories(selected.resolve("target"));
        Files.writeString(selected.resolve("target/a"), "data");
        Files.writeString(selected.resolve("excluded"), "not requested");
        var source = new SelectedDirectory(selected);
        Path payload;
        try (var first = work(); var second = work()) {
            first.prepare();
            second.prepare();
            first.capture(source, new SnapshotRequest(List.of("target/a", ".env"), 11), () -> false);
            second.capture(source, new SnapshotRequest(List.of(".env", "target/a"), 11), () -> false);
            assertEquals(first.sha256(), second.sha256());
            payload = first.directory();
            assertFalse(Files.exists(payload.resolve("excluded")));
            assertTrue(Files.readAttributes(payload.resolve(".env"), DosFileAttributes.class).isReadOnly());
            Files.writeString(selected.resolve(".env"), "changed source");
            first.verify(() -> false);
            assertEquals("fixture", Files.readString(payload.resolve(".env")));
            Files.setAttribute(payload.resolve(".env"), "dos:readonly", false);
            Files.writeString(payload.resolve(".env"), "tampered");
            assertThrows(IOException.class, () -> first.verify(() -> false));
        }
        assertFalse(Files.exists(payload));
        assertEquals("changed source", Files.readString(selected.resolve(".env")));
    }

    @Test
    void requestAndOwnershipFailuresPreserveUnrelatedFilesAndCanBeRetried() throws Exception {
        assertThrows(IllegalArgumentException.class,
            () -> FrozenSourceWork.validate(new SnapshotRequest(List.of("a", "a"), 20)));
        assertThrows(IllegalArgumentException.class,
            () -> FrozenSourceWork.validate(new SnapshotRequest(List.of(), FrozenSourceWork.MAX_BYTES + 1)));
        assertThrows(IllegalArgumentException.class,
            () -> FrozenSourceWork.validate(new SnapshotRequest(Collections.nCopies(100001, "a"), 0)));
        var task = UUID.randomUUID();
        var id = UUID.randomUUID();
        var workRoot = root.resolve("work");
        Path selected = Files.createDirectory(root.resolve("selected"));
        Path container = workRoot.resolve(task.toString()).resolve(id.toString());
        try (var original = new FrozenSourceWork(workRoot, task, id, "owner")) {
            original.prepare();
            original.capture(new SelectedDirectory(selected), new SnapshotRequest(List.of(), 0), () -> false);
            byte[] marker = Files.readAllBytes(container.resolve("owner.bin"));
            Files.writeString(container.resolve("owner.bin"), "not the owner");
            try (var recovered = new FrozenSourceWork(workRoot, task, id, "owner")) {
                assertThrows(IOException.class, recovered::recover);
            }
            assertTrue(Files.isDirectory(container));
            Files.write(container.resolve("owner.bin"), marker);
            try (var recovered = new FrozenSourceWork(workRoot, task, id, "owner")) {
                recovered.recover();
            }
            assertFalse(Files.exists(container));
        }
        try (var missing = new FrozenSourceWork(workRoot, task, id, "owner")) {
            missing.recover();
        }
    }

    @Test
    void frozenPayloadRejectsDirectoryJunctionWithoutFollowingItDuringCleanup() throws Exception {
        var outside = Files.createDirectory(root.resolve("outside"));
        Files.writeString(outside.resolve("keep"), "keep");
        var selected = Files.createDirectory(root.resolve("selected"));
        try (var work = work()) {
            work.prepare();
            work.capture(new SelectedDirectory(selected), new SnapshotRequest(List.of(), 0), () -> false);
            var link = work.directory().resolve("unexpected");
            try {
                junction(link, outside);
                assertThrows(IOException.class, () -> work.verify(() -> false));
            } finally {
                Files.deleteIfExists(link);
            }
        }
        assertEquals("keep", Files.readString(outside.resolve("keep")));
    }

    @Test
    void replacedWorkIdentityAndLinkedPayloadCannotAcquireTheOriginalCleanupAuthority() throws Exception {
        var selected = Files.createDirectory(root.resolve("selected"));
        var task = UUID.randomUUID();
        var id = UUID.randomUUID();
        var workRoot = root.resolve("work");
        try (var work = new FrozenSourceWork(workRoot, task, id, "owner")) {
            work.prepare();
            work.capture(new SelectedDirectory(selected), new SnapshotRequest(List.of(), 0), () -> false);
            var container = work.directory().getParent();
            var original = root.resolve("retained-original");
            assertTrue(container.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()));
            assertTrue(original.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()));
            Files.move(container, original);
            Files.createDirectory(container);
            Files.copy(original.resolve("owner.bin"), container.resolve("owner.bin"));
            try {
                assertThrows(IOException.class, work::close);
                try (var recovery = new FrozenSourceWork(workRoot, task, id, "owner")) {
                    assertThrows(IOException.class, recovery::recover);
                }
                assertTrue(Files.exists(container.resolve("owner.bin")));
            } finally {
                Files.delete(container.resolve("owner.bin"));
                Files.delete(container);
                Files.move(original, container);
            }
            Files.delete(work.directory());
            try {
                junction(work.directory(), selected);
                assertThrows(IOException.class, () -> work.verify(() -> false));
            } finally {
                Files.deleteIfExists(work.directory());
                Files.createDirectory(work.directory());
            }
        }
        assertTrue(Files.isDirectory(selected));
    }

    @Test
    void listingIsCompleteSortedNonrecursiveAndDoesNotApplyDeploymentExclusions() throws Exception {
        Files.createDirectory(root.resolve("target"));
        Files.writeString(root.resolve("target/child.txt"), "nested");
        Files.writeString(root.resolve(".env"), "fixture");
        Files.writeString(root.resolve("中文.txt"), "abc");
        var source = new SelectedDirectory(root);
        var entries = source.list("", 3, () -> false);
        assertEquals(List.of(".env", "target", "中文.txt"), entries.stream()
            .map(gold.debug.windowstolinux.appapi.file.FileEntry::relativePath).toList());
        assertEquals(gold.debug.windowstolinux.appapi.file.FileEntry.EntryType.DIRECTORY,
            entries.get(1).type());
        assertEquals(3, entries.get(2).byteCount());
        assertThrows(UnsupportedOperationException.class, () -> entries.clear());
        assertEquals("target/child.txt", source.list("target", 1, () -> false).getFirst().relativePath());
        assertThrows(IOException.class, () -> source.list("", 2, () -> false));
        assertThrows(IOException.class, () -> source.list(".env", 3, () -> false));
        assertThrows(IllegalArgumentException.class, () -> source.list("", 0, () -> false));
        assertThrows(IllegalArgumentException.class, () -> source.list("", 100001, () -> false));
        assertThrows(IllegalArgumentException.class, () -> source.list("../outside", 1, () -> false));
        assertThrows(CancellationException.class, () -> source.list("", 3, () -> true));
    }

    @Test
    void listingReportsJunctionsWithoutTraversingThemAndRejectsEscapingDirectoryRequests() throws Exception {
        Path selected = Files.createDirectory(root.resolve("selected"));
        Path inside = Files.createDirectory(selected.resolve("inside"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.writeString(inside.resolve("child"), "inside");
        Files.writeString(outside.resolve("untouched"), "outside");
        Path safe = selected.resolve("alias"), escape = selected.resolve("escape");
        try {
            junction(safe, inside);
            junction(escape, outside);
            var source = new SelectedDirectory(selected);
            var entries = source.list("", 3, () -> false);
            assertEquals(List.of("alias", "escape", "inside"), entries.stream()
                .map(gold.debug.windowstolinux.appapi.file.FileEntry::relativePath).toList());
            assertEquals(gold.debug.windowstolinux.appapi.file.FileEntry.EntryType.OTHER,
                entries.getFirst().type());
            assertEquals(gold.debug.windowstolinux.appapi.file.FileEntry.EntryType.OTHER,
                entries.get(1).type());
            assertEquals("alias/child", source.list("alias", 1, () -> false).getFirst().relativePath());
            assertThrows(IOException.class, () -> source.list("escape", 1, () -> false));
        } finally {
            Files.deleteIfExists(safe);
            Files.deleteIfExists(escape);
        }
        assertEquals("outside", Files.readString(outside.resolve("untouched")));
    }

    @Test
    void listingRejectsAReplacedRootOrDirectoryChangedDuringEnumeration() throws Exception {
        Path selected = Files.createDirectory(root.resolve("selected"));
        Files.writeString(selected.resolve("file"), "original");
        var source = new SelectedDirectory(selected);
        var signals = new java.util.concurrent.atomic.AtomicInteger();
        FileTime before = Files.getLastModifiedTime(selected);
        assertThrows(IOException.class, () -> source.list("", 1, () -> {
            if (signals.incrementAndGet() == 2) {
                try {
                    Files.setLastModifiedTime(selected, FileTime.fromMillis(before.toMillis() + 5000));
                } catch (IOException failure) {
                    throw new AssertionError(failure);
                }
            }
            return false;
        }));
        Files.move(selected, root.resolve("original"));
        Files.createDirectory(selected);
        Files.writeString(selected.resolve("file"), "replacement");
        assertThrows(IOException.class, () -> source.list("", 1, () -> false));
        assertEquals("original", Files.readString(root.resolve("original/file")));
    }

    FrozenSourceWork work() {
        return new FrozenSourceWork(root.resolve("work"), UUID.randomUUID(), UUID.randomUUID(), "fixture.mod");
    }
}
