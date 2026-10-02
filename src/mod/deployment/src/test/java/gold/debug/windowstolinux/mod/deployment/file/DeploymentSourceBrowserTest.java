package gold.debug.windowstolinux.mod.deployment.file;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks original content identity when actual selected source bytes change. / 在实际选中源码字节改变时核查原内容身份。 */
class DeploymentSourceBrowserTest {
    @TempDir
    Path root;

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
