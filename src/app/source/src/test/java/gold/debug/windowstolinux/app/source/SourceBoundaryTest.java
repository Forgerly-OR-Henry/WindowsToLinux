package gold.debug.windowstolinux.app.source;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.*;

import gold.debug.windowstolinux.app.source.boundary.SelectedDirectory;
import gold.debug.windowstolinux.app.source.snapshot.FrozenSourceWork;
import gold.debug.windowstolinux.appapi.source.SnapshotRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceBoundaryTest {
    @TempDir
    Path root;

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

    FrozenSourceWork work() {
        return new FrozenSourceWork(root.resolve("work"), UUID.randomUUID(), UUID.randomUUID(), "fixture.mod");
    }
}
