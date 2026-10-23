package gold.debug.windowstolinux.mod.deployment.file;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks original content identity when actual selected source bytes change. / 在实际选中源码字节改变时核查原内容身份。 */
class DeploymentSourceBrowserTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(strings = {"Authorization:", "aUtHoRiZaTiOn =", "Proxy-Authorization:"})
    void basicAuthorizationIsExcludedFromReadSearchAndPagesWhileOriginalDigestIsRetained(String header) throws Exception {
        String encoded = "Zml4dHVyZTpwYXNzd29yZA==";
        String source = "GET /fixture HTTP/1.1\n" + header + " Basic " + encoded + "\nordinary source\n";
        byte[] bytes = source.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(root.resolve("request.http"), bytes);
        var browser = new DeploymentSourceBrowser(root, "a".repeat(64));
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var read = browser.read("request.http", 0, 10);
        var page = browser.readPage("request.http", 0, 10, 4000);
        assertEquals(List.of("1: GET /fixture HTTP/1.1", "2: [credential content excluded]", "3: ordinary source"), read);
        assertEquals(read, page.lines());
        assertEquals(digest, page.sourceSha256());
        assertEquals(digest, browser.contentSha256("request.http"));
        assertEquals(3, page.nextLine());
        assertFalse(page.hasMore());
        assertTrue(browser.search("Basic", 0, 10).isEmpty());
        assertTrue(browser.search(encoded, 0, 10).isEmpty());
        assertEquals(List.of("request.http:2: [credential content excluded]"), browser.search("credential content excluded", 0, 10));
    }

    @Test
    void recursiveIndexPagesExposeActualScopeAndNextWithoutReadCoverage() throws Exception {
        Files.createDirectories(root.resolve("backend/src/deep"));
        for (int i = 0; i < 23; i++)
            Files.writeString(root.resolve("backend/src/deep/" + i + "汉字🙂.txt"), "PRIVATE_BODY_NOT_INDEXED\n");
        Files.writeString(root.resolve("outside.txt"), "outside selected directory\n");
        var browser = new DeploymentSourceBrowser(root, "a".repeat(64));
        var selected = new java.util.ArrayList<String>();
        int next = 0;
        do {
            var page = browser.indexPage("backend/", next, 3);
            assertEquals(next, page.offset());
            assertEquals(23, page.total());
            assertTrue(page.metadataOnly());
            assertEquals("backend", page.directory());
            assertEquals("a".repeat(64), page.sourceRevision());
            assertEquals(page.next() < page.total(), page.hasMore());
            String encoded = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(page);
            assertFalse(encoded.contains("PRIVATE_BODY_NOT_INDEXED"));
            assertTrue(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 12000);
            selected.addAll(page.paths());
            assertTrue(page.next() > next);
            next = page.next();
        } while (next < 23);
        assertEquals(23, new java.util.HashSet<>(selected).size());
        assertTrue(selected.stream().allMatch(path -> path.startsWith("backend/src/deep/")));
        assertFalse(browser.indexPage("missing", 0, 3).hasMore());
        assertThrows(IllegalArgumentException.class, () -> browser.indexPage("backend", 24, 3));
        for (String bad : List.of("/", "../", "backend//", "backend/../"))
            assertThrows(SecurityException.class, () -> browser.indexPage(bad, 0, 3), bad);
        assertEquals(List.of("backend/", "outside.txt"), browser.list("", 0, 3), "Shared immediate-list behavior remains intact");
        Files.writeString(root.resolve("new.txt"), "outside frozen membership\n");
        assertThrows(SecurityException.class, () -> browser.indexPage("backend", 0, 3));
    }

    @Test
    void searchPagesResumeInsideFilesWithoutSkippingMatches() throws Exception {
        String first = java.util.stream.IntStream.range(0, 151).mapToObj(i -> "needle 汉字🙂 " + i)
            .collect(java.util.stream.Collectors.joining("\n"));
        Files.writeString(root.resolve("a.txt"), first + "\nneedle" + "汉".repeat(3993) + "🙂tail\n");
        Files.write(root.resolve("b.bin"), new byte[]{0, 1, 2});
        Files.writeString(root.resolve("c.txt"), "needle final\nAuthorization: Basic PUBLIC_EXCLUDED\n");
        var browser = new DeploymentSourceBrowser(root, "a".repeat(64));
        var all = new java.util.ArrayList<DeploymentSourceBrowser.SearchMatch>();
        var unavailable = new java.util.ArrayList<DeploymentSourceBrowser.SearchSkipped>();
        var cursor = new DeploymentSourceBrowser.SearchCursor(0, 0);
        boolean inside = false, ended = false;
        for (int pages = 0; pages < 20; pages++) {
            var page = browser.searchPage("needle", cursor.file(), cursor.line(), 1);
            assertEquals(cursor, page.start());
            assertEquals(3, page.totalFiles());
            assertTrue(page.metadataOnly());
            var encoded = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(page);
            assertTrue(encoded.length() <= 12000 && encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 12000);
            java.nio.charset.StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(encoded));
            all.addAll(page.matches());
            unavailable.addAll(page.unavailable());
            assertNotEquals(cursor, page.next());
            inside |= page.next().file() == 0 && page.next().line() > 0;
            cursor = page.next();
            if (!page.hasMore()) {
                ended = true;
                break;
            }
        }
        assertTrue(ended && inside);
        assertEquals(153, all.size());
        assertEquals(153, all.stream().map(match -> match.path() + ":" + match.line()).distinct().count());
        assertEquals(1, unavailable.size());
        assertEquals("b.bin", unavailable.getFirst().path());
        assertEquals("TEXT_UNAVAILABLE", unavailable.getFirst().status());
        assertFalse(all.get(151).previewComplete());
        assertEquals("c.txt", all.getLast().path());
        var noHit = browser.searchPage("absent", 0, 0, 1);
        assertTrue(noHit.matches().isEmpty());
        assertEquals(new DeploymentSourceBrowser.SearchCursor(1, 0), noHit.next());
        assertTrue(noHit.hasMore());
        assertTrue(browser.searchPage("Basic", 2, 0, 1).matches().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> browser.searchPage("needle", 0, 1000000, 1));
        assertThrows(IllegalArgumentException.class, () -> browser.searchPage("needle", 3, 1, 1));
        assertEquals(100, browser.search("needle", 0, 1).size(), "The shared legacy search result is unchanged");
        Files.writeString(root.resolve("a.txt"), "changed frozen source\n");
        assertThrows(SecurityException.class, () -> browser.searchPage("needle", 0, 0, 1));
    }

    @Test
    void pathIndexFindsNestedMembersWithStableBoundedPagesWithoutReadingTheirContents() throws Exception {
        Files.createDirectories(root.resolve("backend/src/main/kotlin/demo"));
        Files.writeString(root.resolve("backend/src/main/kotlin/demo/Main.kt"), "actual source body\n");
        Files.writeString(root.resolve("backend/build.gradle.kts"), "actual manifest body\n");
        var browser = new DeploymentSourceBrowser(root, "a".repeat(64));
        String first = browser.index("backend/", 0, 1);
        String second = browser.index("backend/", 1, 1);
        assertTrue(first.contains("metadataOnly=true total=2 next=1"));
        assertTrue(first.endsWith("backend/build.gradle.kts\n"));
        assertTrue(second.endsWith("backend/src/main/kotlin/demo/Main.kt\n"));
        assertTrue(second.contains("next=2"));
        assertFalse((first + second).contains("actual source body"));
        assertFalse(first.contains("sourceSha256="));
        assertTrue(browser.index("", 0, 100).length() < 6000);
        assertThrows(SecurityException.class, () -> browser.index("../", 0, 10));
        Files.writeString(root.resolve("added.txt"), "outside frozen inventory\n");
        assertThrows(SecurityException.class, () -> browser.index("", 0, 10));
    }

    @Test
    void memberDigestUsesOriginalBytesAndRefusesChangedOrUnownedFiles() throws Exception {
        byte[] bytes = "password=fixture-secret\r\nnormal source\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(root.resolve("settings.txt"), bytes);
        var browser = new DeploymentSourceBrowser(root, "a".repeat(64));
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(digest, browser.contentSha256("settings.txt"));
        assertFalse(browser.read("settings.txt", 0, 1).toString().contains("fixture-secret"));
        assertNotEquals(digest, java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(browser.read("settings.txt", 0, 1).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertThrows(DeploymentSourceBrowser.MissingMemberException.class, () -> browser.contentSha256("absent.txt"));
        assertThrows(SecurityException.class, () -> browser.contentSha256("../settings.txt"));
        Files.writeString(root.resolve("settings.txt"), "changed bytes\n");
        assertThrows(SecurityException.class, () -> browser.contentSha256("settings.txt"));
    }

    @Test
    void readPagesExposeActualRemainingLinesAndStableOriginalDigest() throws Exception {
        String source = java.util.stream.IntStream.rangeClosed(1, 134).mapToObj(line -> "source line " + line)
            .collect(java.util.stream.Collectors.joining("\n")) + "\nTAIL_ENTRYPOINT_FACT\n";
        Files.writeString(root.resolve("main.py"), source);
        var browser = new DeploymentSourceBrowser(root, "a".repeat(64));
        var legacy = browser.read("main.py", 0, 100);
        var first = browser.readPage("main.py", 0, 100, 11000);
        assertEquals(135, first.totalLines());
        assertEquals(0, first.offset());
        assertEquals(100, first.nextLine());
        assertTrue(first.hasMore());
        assertEquals(legacy, first.lines());
        assertFalse(first.lines().toString().contains("TAIL_ENTRYPOINT_FACT"));
        var last = browser.readPage("main.py", first.nextLine(), 100, 11000);
        assertEquals(100, last.offset());
        assertEquals(135, last.nextLine());
        assertFalse(last.hasMore());
        assertEquals(35, last.lines().size());
        assertEquals("135: TAIL_ENTRYPOINT_FACT", last.lines().getLast());
        assertEquals(browser.contentSha256("main.py"), first.sourceSha256());
        assertEquals(first.sourceSha256(), last.sourceSha256());
        assertEquals(last, browser.readPage("main.py", 100, 99, 11000), "A changed requested limit does not invent a different actual last page");
        assertEquals(legacy, browser.read("main.py", 0, 100));
        Files.writeString(root.resolve("main.py"), "changed frozen member\n");
        assertThrows(SecurityException.class, () -> browser.readPage("main.py", 100, 100, 11000));
    }

    @Test
    void readPageBodyBudgetStopsAfterWholeLinesWhileLegacyReadKeepsItsOwnTruncation() throws Exception {
        Files.writeString(root.resolve("long.txt"), "A".repeat(4005) + "\n" + "B".repeat(4005) + "\n" + "C".repeat(2000) + "\nend\n");
        Files.writeString(root.resolve("budget.txt"), ("x".repeat(3995) + "\n").repeat(4));
        var browser = new DeploymentSourceBrowser(root, "a".repeat(64));
        var legacy = List.of("1: " + "A".repeat(4000) + " [line truncated]", "2: " + "B".repeat(4000) + " [line truncated]",
            "3: " + "C".repeat(2000), "4: end");
        assertEquals(legacy, browser.read("long.txt", 0, 100));
        assertEquals(java.util.stream.IntStream.rangeClosed(1, 3).mapToObj(line -> line + ": " + "x".repeat(3995)).toList(),
            browser.read("budget.txt", 0, 100), "The shared reader retains its original 12000-character accumulation");
        assertEquals(2, browser.readPage("budget.txt", 0, 100, 11000).nextLine());
        var first = browser.readPage("long.txt", 0, 100, 9000);
        assertEquals(2, first.nextLine());
        assertTrue(first.hasMore());
        assertEquals(List.of("1: " + "A".repeat(4005), "2: " + "B".repeat(4005)), first.lines());
        assertTrue(String.join("\n", first.lines()).length() <= 9000);
        var last = browser.readPage("long.txt", first.nextLine(), 100, 9000);
        assertEquals(List.of("3: " + "C".repeat(2000), "4: end"), last.lines());
        assertEquals(4, last.nextLine());
        assertFalse(last.hasMore());
        assertEquals(legacy, browser.read("long.txt", 0, 100));
    }

    @Test
    void oversizedFirstReadLineFailsWithoutClaimingEofOrChangingLegacyBehavior() throws Exception {
        Files.writeString(root.resolve("oversized.txt"), "x".repeat(11000) + "\nactual tail\n");
        var browser = new DeploymentSourceBrowser(root, "a".repeat(64));
        var rejected = assertThrows(IllegalArgumentException.class, () -> browser.readPage("oversized.txt", 0, 100, 11000));
        assertTrue(rejected.getMessage().contains("no source lines were read and EOF is unconfirmed"));
        assertEquals(List.of("1: " + "x".repeat(4000) + " [line truncated]", "2: actual tail"), browser.read("oversized.txt", 0, 100));
        assertEquals(List.of("2: actual tail"), browser.readPage("oversized.txt", 1, 100, 11000).lines());
    }

    @Test
    void membershipUsesFrozenRegularFilesAndRejectsChangedBoundaries() throws Exception {
        Files.createDirectories(root.resolve("component"));
        Files.writeString(root.resolve("component/source.txt"), "frozen content\n");
        var browser = new DeploymentSourceBrowser(root, "a".repeat(64));
        assertTrue(browser.containsFile("component/source.txt"));
        assertFalse(browser.containsFile("component"));
        assertFalse(browser.containsFile("absent.txt"));
        assertThrows(SecurityException.class, () -> browser.containsFile("../source.txt"));
        Files.writeString(root.resolve("added.txt"), "not frozen\n");
        assertThrows(SecurityException.class, () -> browser.containsFile("absent.txt"));
    }

    @Test
    void absentQueryIsDistinctFromADeletedFrozenMember() throws Exception {
        Files.writeString(root.resolve("README.md"), "original source\n");
        var browser = new DeploymentSourceBrowser(root, "a".repeat(64));
        var missing = assertThrows(DeploymentSourceBrowser.MissingMemberException.class,
            () -> browser.read("absent.txt", 0, 10));
        assertFalse(missing.getMessage().contains(root.toString()));
        assertEquals(gold.debug.windowstolinux.appapi.task.result.ProblemCode.NOT_FOUND, missing.problem().code());
        assertEquals("DEPLOYMENT_SOURCE_MEMBER_MISSING", missing.problem().eventCode());
        assertEquals(missing.getMessage(), missing.problem().message());
        assertTrue(missing.problem().context().isEmpty());
        assertTrue(missing.problem().diagnosticId().isEmpty());
        assertThrows(SecurityException.class, () -> browser.read("../absent.txt", 0, 10));
        Files.delete(root.resolve("README.md"));
        assertThrows(SecurityException.class, () -> browser.read("README.md", 0, 10));
        assertThrows(SecurityException.class, () -> browser.read("absent.txt", 0, 10));
    }

    @Test
    void emittedDirectoriesCanBeListedAgainWithoutAllowingTraversal() throws Exception {
        Files.createDirectories(root.resolve("backend/src"));
        Files.writeString(root.resolve("backend/pom.xml"), "<project/>\n");
        Files.writeString(root.resolve("backend/src/Main.java"), "class Main {}\n");
        var browser = new DeploymentSourceBrowser(root, "a".repeat(64));
        String backend = browser.list("", 0, 10).getFirst();
        assertEquals("backend/", backend);
        assertEquals(List.of("backend/pom.xml", "backend/src/"), browser.list(backend, 0, 10));
        assertEquals(browser.list("backend", 0, 10), browser.list(backend, 0, 10));
        assertEquals(List.of("backend/src/Main.java"), browser.list("backend/src/", 0, 10));
        for (String invalid : List.of("/", "/backend/", "../", "backend/../", "backend//", "backend/./", "backend\\"))
            assertThrows(SecurityException.class, () -> browser.list(invalid, 0, 10), invalid);
        assertThrows(SecurityException.class, () -> browser.read("backend/", 0, 10));
    }

    /** Search cannot hide a changed selected file as an unsupported text file. / 搜索不能把改变的选中文件掩盖成不支持的文本文件。
     * @param search whether the search path is used / 是否使用搜索路径
     * @throws Exception if real file or frozen browser setup fails / 真实文件或冻结浏览器设置失败时 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void changedSelectedContentStopsReadAndSearch(boolean search) throws Exception {
        Path source = root.resolve("README.md");
        Files.writeString(source, "actual build instructions\n");
        var browser = new DeploymentSourceBrowser(root, "a".repeat(64));
        assertEquals(1, browser.search("build", 0, 10).size());
        Files.writeString(source, "altered build instructions\n");
        assertThrows(SecurityException.class, () -> {
            if (search)
                browser.search("build", 0, 10);
            else
                browser.read("README.md", 0, 10);
        });
    }
}
