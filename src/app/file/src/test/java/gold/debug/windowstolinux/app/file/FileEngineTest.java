package gold.debug.windowstolinux.app.file;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import gold.debug.windowstolinux.appapi.file.*;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.app.windows.file.WindowsLinks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

class FileEngineTest {
    @TempDir
    Path root;

    final LocalFileBackend backend = new LocalFileBackend();

    final FileEngine engine = new FileEngine(backend);

    final Journal journal = new Journal();

    final FileEngine.Decisions decisions = new FileEngine.Decisions() {
        public FileChoice.Directory directory(FileInfo a, FileInfo b) {
            return FileChoice.Directory.MERGE;
        }

        public FileChoice.Conflict file(FileInfo a, FileInfo b) {
            return FileChoice.Conflict.OVERWRITE;
        }
    };

    FilePath path(String name) {
        return FilePath.local(root.resolve(name));
    }

    void file(String name, String contents) throws IOException {
        Path p = root.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, contents);
    }

    FileReport copy(String from, String to, boolean link, boolean move, FileChoice choice) throws Exception {
        return engine.copy(path(from), path(to), link, move, choice, engine, decisions, (s, t, p) -> {
        }, journal, () -> false, (a, b) -> {
        });
    }

    @AfterEach
    void removeNativeJunctionsBeforeJUnitCleanup() throws IOException {
        removeJunctions(root);
    }

    static void removeJunctions(Path directory) throws IOException {
        try (var children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                if (WindowsLinks.target(child).isPresent()) Files.delete(child);
                else if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) removeJunctions(child);
            }
        }
    }

    @Test
    void readsActualContentAndBoundsQueryWithoutAGlobalRoot() throws Exception {
        file("outside-selection/a.txt", "abc");
        file("outside-selection/z.txt", "z");
        assertArrayEquals("abc".getBytes(), engine.read(path("outside-selection/a.txt"), 3, () -> false));
        assertThrows(IOException.class, () -> engine.read(path("outside-selection/a.txt"), 2, () -> false));
        assertEquals(List.of("a.txt"), engine.list(path("outside-selection"), 3, Optional.of(FileInfo.Kind.FILE), "a.", () -> false).stream().map(i -> i.actual().name()).toList());
        assertThrows(IOException.class, () -> engine.list(path("outside-selection"), 1, Optional.empty(), "", () -> false));
    }

    @Test
    void defaultConflictChoicesAskOnlyForUnspecifiedLayersAndNeverForAbsentTargets() throws Exception {
        file("source", "incoming");
        file("target", "old");
        file("from/a", "incoming");
        file("to/a", "old");
        var questions = new ArrayList<String>();
        var presenter = new FileEngine.Decisions() {
            public FileChoice.Directory directory(FileInfo source, FileInfo target) {
                questions.add("directory");
                return FileChoice.Directory.MERGE;
            }

            public FileChoice.Conflict file(FileInfo source, FileInfo target) {
                questions.add("file");
                return FileChoice.Conflict.COPY;
            }
        };
        var approval = (FileEngine.Approval) (source, type, plan) -> {
        };
        assertEquals(Outcome.SUCCEEDED, engine.copy(path("source"), path("absent"), false, false, FileChoice.ask(), engine, presenter, approval, journal, () -> false, (a, b) -> {
        }).outcome());
        assertTrue(questions.isEmpty());
        var copied = engine.copy(path("source"), path("target"), false, false, FileChoice.ask(), engine, presenter, approval, journal, () -> false, (a, b) -> {
        });
        assertEquals(Outcome.SUCCEEDED, copied.outcome());
        assertEquals(List.of("file"), questions);
        assertEquals("old", Files.readString(root.resolve("target")));
        assertEquals("incoming", Files.readString(root.resolve("target (1)")));
        questions.clear();
        assertEquals(Outcome.SUCCEEDED, engine.copy(path("from"), path("to"), false, false, new FileChoice(Optional.of(FileChoice.Directory.MERGE), Optional.empty()), engine, presenter, approval, journal, () -> false, (a, b) -> {
        }).outcome());
        assertEquals(List.of("file"), questions);
        questions.clear();
        assertEquals(Outcome.SUCCEEDED, engine.copy(path("from"), path("to"), false, false, FileChoice.of(FileChoice.Directory.MERGE, FileChoice.Conflict.OVERWRITE), engine, presenter, approval, journal, () -> false, (a, b) -> {
        }).outcome());
        assertTrue(questions.isEmpty());
    }

    @Test
    void nativeBrokenLinksRemainLinkObjectsAndFileResolutionRejectsActualCycles() throws Exception {
        file("target/content", "before removal");
        Files.createDirectory(root.resolve("other"));
        WindowsLinks.create(root.resolve("broken"), root.resolve("target"));
        Files.delete(root.resolve("target/content"));
        Files.delete(root.resolve("target"));
        assertEquals(FileInfo.Kind.LINK, engine.inspect(path("broken"), true, () -> false).kind());
        assertThrows(FileNotFoundException.class, () -> engine.inspect(path("broken"), false, () -> false));
        assertEquals(Outcome.SUCCEEDED, copy("broken", "other/copied", true, false, FileChoice.ask()).outcome());
        assertEquals(root.resolve("target"), Path.of(FileEngine.resolveTarget(path("other/copied"), engine.inspect(path("other/copied"), true, () -> false).target().orElseThrow()).path()));
        Files.createDirectory(root.resolve("a"));
        Files.createDirectory(root.resolve("b"));
        WindowsLinks.create(root.resolve("link-a"), root.resolve("a"));
        WindowsLinks.create(root.resolve("link-b"), root.resolve("b"));
        Files.delete(root.resolve("a"));
        Files.move(root.resolve("link-b"), root.resolve("a"));
        Files.delete(root.resolve("b"));
        Files.move(root.resolve("link-a"), root.resolve("b"));
        assertThrows(IOException.class, () -> engine.inspect(path("a"), false, () -> false));
        assertEquals(FileInfo.Kind.LINK, engine.inspect(path("a"), true, () -> false).kind());
    }

    @Test
    void deletingActualRegisteredTargetLeavesItsLinkUnchangedAndMarksAssociationUnverified() throws Exception {
        file("target/content", "remove");
        WindowsLinks.create(root.resolve("managed"), root.resolve("target"));
        var link = engine.inspect(path("managed"), true, () -> false);
        journal.saveLink(link, engine.optionalTarget(link, () -> false));
        assertEquals(Outcome.SUCCEEDED, engine.delete(engine.inspect(path("target"), false, () -> false), false, DeleteEffect.PERMANENT, journal, () -> false).outcome());
        assertEquals(link.identity(), engine.inspect(path("managed"), true, () -> false).identity());
        assertFalse(journal.targets.containsKey(path("managed")));
        assertTrue(journal.links.containsKey(path("managed")));
    }

    @Test
    void directoryMergeKeepsUniqueTargetAndSkipContinuesIndependentItems() throws Exception {
        file("from/a.txt", "incoming");
        file("from/b.txt", "independent");
        file("to/a.txt", "existing");
        file("to/unique.txt", "keep");
        var result = copy("from", "to", false, false, FileChoice.of(FileChoice.Directory.MERGE, FileChoice.Conflict.SKIP));
        assertEquals(Outcome.FAILED, result.outcome());
        assertEquals("existing", Files.readString(root.resolve("to/a.txt")));
        assertEquals("independent", Files.readString(root.resolve("to/b.txt")));
        assertEquals("keep", Files.readString(root.resolve("to/unique.txt")));
        assertTrue(Files.exists(root.resolve("from/a.txt")));
    }

    @Test
    void directorySkipLeavesBothSidesAndNeverProcessesChildren() throws Exception {
        file("from/a.txt", "in");
        file("to/a.txt", "old");
        var result = copy("from", "to", false, true, FileChoice.of(FileChoice.Directory.SKIP, FileChoice.Conflict.OVERWRITE));
        assertEquals(Outcome.FAILED, result.outcome());
        assertEquals(1, result.items().size());
        assertEquals("in", Files.readString(root.resolve("from/a.txt")));
        assertEquals("old", Files.readString(root.resolve("to/a.txt")));
    }

    @Test
    void directoryCopyUsesActualNewNamesAndPreservesInternalAndExternalLinks() throws Exception {
        file("from/inside/payload.txt", "inside");
        file("outside/payload.txt", "outside");
        file("to/old.txt", "keep");
        WindowsLinks.create(root.resolve("from/internal"), Path.of("inside"));
        WindowsLinks.create(root.resolve("from/external"), root.resolve("outside"));
        var result = copy("from", "to", false, false, FileChoice.of(FileChoice.Directory.COPY, FileChoice.Conflict.COPY));
        assertEquals(Outcome.SUCCEEDED, result.outcome());
        assertEquals("inside", Files.readString(root.resolve("to (1)/internal/payload.txt")));
        assertEquals("outside", Files.readString(root.resolve("to (1)/external/payload.txt")));
        assertEquals(root.resolve("to (1)/inside"), FileEngine.resolveTarget(path("to (1)/internal"), engine.inspect(path("to (1)/internal"), true, () -> false).target().orElseThrow()).server().isEmpty() ? Path.of(FileEngine.resolveTarget(path("to (1)/internal"), engine.inspect(path("to (1)/internal"), true, () -> false).target().orElseThrow()).path()) : null);
        assertEquals("keep", Files.readString(root.resolve("to/old.txt")));
        assertEquals("inside", Files.readString(root.resolve("from/internal/payload.txt")));
    }

    @Test
    void skippedInternalTargetDoesNotPublishAMisleadingLink() throws Exception {
        file("from/inside/payload", "new");
        file("to/inside/payload", "old");
        WindowsLinks.create(root.resolve("from/dependent"), root.resolve("from/inside"));
        var result = copy("from", "to", false, false, FileChoice.of(FileChoice.Directory.MERGE, FileChoice.Conflict.SKIP));
        assertEquals(Outcome.FAILED, result.outcome());
        assertEquals("old", Files.readString(root.resolve("to/inside/payload")));
        // A directory link may be published only when all required contents have succeeded.
        assertFalse(Files.exists(root.resolve("to/dependent"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void standaloneLinkCopyAndMoveKeepOriginalTargetAndDoNotReadIt() throws Exception {
        file("target/payload", "original");
        Files.createDirectory(root.resolve("other"));
        WindowsLinks.create(root.resolve("link"), root.resolve("target"));
        assertEquals(Outcome.SUCCEEDED, copy("link", "other/copy", true, false, FileChoice.ask()).outcome());
        assertEquals("original", Files.readString(root.resolve("other/copy/payload")));
        assertEquals(Outcome.SUCCEEDED, copy("other/copy", "moved-link", true, true, FileChoice.ask()).outcome());
        assertFalse(Files.exists(root.resolve("other/copy"), LinkOption.NOFOLLOW_LINKS));
        assertEquals("original", Files.readString(root.resolve("moved-link/payload")));
    }

    @Test
    void fileRootLinkFollowsActualObjectWhileDirectoryChildrenRemainLinks() throws Exception {
        file("actual/a", "content");
        WindowsLinks.create(root.resolve("alias"), root.resolve("actual"));
        var result = copy("alias", "copy", false, false, FileChoice.ask());
        assertEquals(Outcome.SUCCEEDED, result.outcome());
        assertEquals(FileInfo.Kind.DIRECTORY, engine.inspect(path("copy"), false, () -> false).kind());
        assertEquals("content", Files.readString(root.resolve("copy/a")));
        assertEquals(FileInfo.Kind.LINK, engine.inspect(path("alias"), true, () -> false).kind());
    }

    @Test
    void targetDirectoryLinkCannotBeTraversedByMerge() throws Exception {
        file("from/inside/a", "new");
        file("to/unique", "keep");
        file("outside/a", "outside");
        WindowsLinks.create(root.resolve("to/inside"), root.resolve("outside"));
        var result = copy("from", "to", false, false, FileChoice.of(FileChoice.Directory.MERGE, FileChoice.Conflict.OVERWRITE));
        assertEquals(Outcome.FAILED, result.outcome());
        assertEquals("outside", Files.readString(root.resolve("outside/a")));
        assertEquals("keep", Files.readString(root.resolve("to/unique")));
    }

    @Test
    void appMovesMaintainRegisteredLinksAndSingleCopiesDoNot() throws Exception {
        file("original/a", "content");
        WindowsLinks.create(root.resolve("registered"), root.resolve("original"));
        FileInfo link = engine.inspect(path("registered"), true, () -> false);
        journal.saveLink(link, engine.optionalTarget(link, () -> false));
        assertEquals(Outcome.SUCCEEDED, copy("original", "copied", false, false, FileChoice.ask()).outcome());
        assertEquals(root.resolve("original"), Path.of(engine.inspect(path("registered"), false, () -> false).actual().path()));
        assertEquals(Outcome.SUCCEEDED, copy("original", "moved", false, true, FileChoice.ask()).outcome());
        assertEquals(root.resolve("moved"), Path.of(engine.inspect(path("registered"), false, () -> false).actual().path()));
        assertFalse(Files.exists(root.resolve("original")));
    }

    @Test
    void partialMovesRetainFailedSourcesAndDoNotRedirectWholeDirectoryLinks() throws Exception {
        file("original/a", "new");
        file("original/b", "independent");
        file("target/a", "old");
        WindowsLinks.create(root.resolve("registered"), root.resolve("original"));
        FileInfo link = engine.inspect(path("registered"), true, () -> false);
        journal.saveLink(link, engine.optionalTarget(link, () -> false));
        var result = copy("original", "target", false, true, FileChoice.of(FileChoice.Directory.MERGE, FileChoice.Conflict.SKIP));
        assertEquals(Outcome.FAILED, result.outcome());
        assertEquals("new", Files.readString(root.resolve("original/a")));
        assertFalse(Files.exists(root.resolve("original/b")));
        assertEquals("independent", Files.readString(root.resolve("target/b")));
        assertEquals(root.resolve("original"), Path.of(engine.inspect(path("registered"), false, () -> false).actual().path()));
    }

    @Test
    void externallyChangedRegisteredLinksBlockMoveBeforeSideEffects() throws Exception {
        file("original/a", "content");
        file("outside/a", "outside");
        WindowsLinks.create(root.resolve("registered"), root.resolve("original"));
        FileInfo link = engine.inspect(path("registered"), true, () -> false);
        journal.saveLink(link, engine.optionalTarget(link, () -> false));
        Files.delete(root.resolve("registered"));
        WindowsLinks.create(root.resolve("registered"), root.resolve("outside"));
        assertThrows(IOException.class, () -> copy("original", "moved", false, true, FileChoice.ask()));
        assertFalse(Files.exists(root.resolve("moved")));
        assertEquals("content", Files.readString(root.resolve("original/a")));
    }

    @Test
    void overwriteKeepsDestinationPathAndUpdatesRegisteredTargetIdentity() throws Exception {
        file("incoming/a", "new");
        file("target/a", "old");
        WindowsLinks.create(root.resolve("link"), root.resolve("target"));
        FileInfo link = engine.inspect(path("link"), true, () -> false);
        journal.saveLink(link, engine.optionalTarget(link, () -> false));
        var result = copy("incoming/a", "target/a", false, false, FileChoice.of(FileChoice.Directory.MERGE, FileChoice.Conflict.OVERWRITE));
        assertEquals(Outcome.SUCCEEDED, result.outcome());
        assertEquals("new", Files.readString(root.resolve("link/a")));
        assertEquals("new", Files.readString(root.resolve("incoming/a")));
    }

    @Test
    void planIsCommittedBeforeFirstSideEffectAndCommitFailureDoesNothing() throws Exception {
        file("source", "content");
        journal.failBegin = true;
        assertThrows(IOException.class, () -> copy("source", "target", false, false, FileChoice.ask()));
        assertFalse(Files.exists(root.resolve("target")));
        assertEquals("content", Files.readString(root.resolve("source")));
    }

    @Test
    void changedTargetAfterApprovalIsRejectedBeforeOverwrite() throws Exception {
        file("source", "new");
        file("target", "old");
        var result = engine.copy(path("source"), path("target"), false, false, FileChoice.of(FileChoice.Directory.MERGE, FileChoice.Conflict.OVERWRITE), engine, decisions, (s, t, p) -> {
            try {
                Files.writeString(root.resolve("target"), "externally changed");
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }, journal, () -> false, (a, b) -> {
        });
        assertEquals(Outcome.FAILED, result.outcome());
        assertEquals("externally changed", Files.readString(root.resolve("target")));
    }

    @Test
    void replacedParentAfterApprovalIsRejectedBeforeWritingToTheNewDirectory() throws Exception {
        file("source", "incoming");
        Files.createDirectory(root.resolve("destination"));
        var result = engine.copy(path("source"), path("destination/new"), false, false, FileChoice.ask(), engine, decisions, (s, t, p) -> {
            try {
                Files.move(root.resolve("destination"), root.resolve("previous-destination"));
                Files.createDirectory(root.resolve("destination"));
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        }, journal, () -> false, (a, b) -> {
        });
        assertEquals(Outcome.FAILED, result.outcome());
        assertFalse(Files.exists(root.resolve("destination/new")));
        assertFalse(journal.phases.contains("BEFORE_WRITE"));
        assertEquals("incoming", Files.readString(root.resolve("source")));
        try (var children = Files.list(root.resolve("destination"))) {
            assertEquals(0, children.count());
        }
    }

    @Test
    void lostAcknowledgementRetainsUnknownEvidenceAndDoesNotReplayMove() throws Exception {
        file("source", "content");
        journal.failWritten = true;
        assertEquals(Outcome.UNKNOWN, copy("source", "target", false, true, FileChoice.ask()).outcome());
        assertEquals("content", Files.readString(root.resolve("source")));
        assertEquals("content", Files.readString(root.resolve("target")));
        assertTrue(journal.phases.contains("BEFORE_STAGING"));
    }

    @Test
    void deletesLinksWithoutFollowingOutsideTargetsAndFileRootTargetsExplicitly() throws Exception {
        file("outside/a", "keep");
        file("folder/user", "remove");
        WindowsLinks.create(root.resolve("folder/link"), root.resolve("outside"));
        var report = engine.delete(engine.inspect(path("folder"), false, () -> false), false, DeleteEffect.PERMANENT, journal, () -> false);
        assertEquals(Outcome.SUCCEEDED, report.outcome());
        assertEquals("keep", Files.readString(root.resolve("outside/a")));
        assertFalse(Files.exists(root.resolve("folder")));
        WindowsLinks.create(root.resolve("alias"), root.resolve("outside"));
        assertEquals(Outcome.SUCCEEDED, engine.delete(engine.inspect(path("alias"), true, () -> false), true, DeleteEffect.PERMANENT, journal, () -> false).outcome());
        assertEquals("keep", Files.readString(root.resolve("outside/a")));
    }

    @Test
    void rejectsSelfDescendantTypeConflictsAndInvalidLinkEntrypoints() throws Exception {
        file("directory/a", "content");
        file("ordinary", "x");
        assertThrows(IOException.class, () -> copy("directory", "directory/sub", false, false, FileChoice.ask()));
        assertThrows(IOException.class, () -> engine.inspect(path("ordinary"), true, () -> false));
        assertEquals(Outcome.FAILED, copy("ordinary", "directory", false, false, FileChoice.ask()).outcome());
        assertEquals("content", Files.readString(root.resolve("directory/a")));
    }

    static class Journal implements FileJournal {
        final Map<FilePath, FileInfo> links = new LinkedHashMap<>();

        final Map<FilePath, String> targets = new HashMap<>();

        final List<String> phases = new ArrayList<>();

        boolean failBegin, failWritten;

        public UUID begin(String t, FilePath s, Optional<FilePath> d, String p) throws IOException {
            if (failBegin) throw new IOException("fixture commit failure");
            phases.add("PLANNED");
            return UUID.randomUUID();
        }

        public void record(UUID o, String phase, FilePath s, Optional<FilePath> t, String detail) throws IOException {
            if (failWritten && phase.equals("WRITTEN")) throw new IOException("fixture lost acknowledgement");
            phases.add(phase);
        }

        public List<FileInfo> links(FilePath host) {
            return links.values().stream().filter(i -> i.actual().server().equals(host.server())).toList();
        }

        public boolean targetMatches(FileInfo link, FileInfo target) {
            return target.identity().equals(targets.get(link.actual()));
        }

        public void saveLink(FileInfo link, Optional<FileInfo> target) {
            links.put(link.actual(), link);
            if (target.isPresent()) targets.put(link.actual(), target.orElseThrow().identity());
            else targets.remove(link.actual());
        }

        public void removeLink(FilePath path) {
            links.remove(path);
            targets.remove(path);
        }
    }
}
