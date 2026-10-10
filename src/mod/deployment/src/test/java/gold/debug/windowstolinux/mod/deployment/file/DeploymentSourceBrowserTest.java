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
