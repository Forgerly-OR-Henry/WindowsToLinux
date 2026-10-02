package gold.debug.windowstolinux.mod.deployment.file.git;

import gold.debug.windowstolinux.appapi.task.ProcessMessage;
import gold.debug.windowstolinux.appapi.task.ProcessSpec;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.mod.ModIdentity;

/** Resolves only this Mod's code, private JSON libraries and the public API for its worker. / 仅为 worker 解析本 Mod 代码、私有 JSON 库及公开 API。 */
final class DeploymentGitLaunch {
    /** Prevents utility construction. / 禁止构造工具实例。 */
    private DeploymentGitLaunch() {
    }

    /** Builds literal launch arguments without inheriting platform runtime internals. / 构建字面启动参数，不继承平台内部运行类。
     * @param identity original Mod identity / 原 Mod 身份
     * @param git exact native Git executable / 精确原生 Git 可执行文件
     * @param checkout original checkout directory / 原检出目录
     * @param control original control directory / 原控制目录
     * @param job original operation identity / 原操作身份
     * @return public owned process launch / 公开所属进程启动
     * @throws IOException if a local code source cannot be resolved / 无法解析本机代码来源时 */
    static ProcessSpec spec(ModIdentity identity, Path git, Path checkout, Path control, UUID job) throws IOException {
        var sources = new LinkedHashSet<String>();
        for (Class<?> type : List.of(DeploymentGitWorker.class, ObjectMapper.class, JsonFactory.class,
            JsonProperty.class, ProcessMessage.class)) {
            try {
                var url = type.getProtectionDomain().getCodeSource().getLocation();
                if (!url.getProtocol().equals("file"))
                    throw new IOException("Nonlocal Git worker code");
                Path path = Path.of(url.toURI()).toRealPath();
                sources.add(path.toString());
            } catch (java.net.URISyntaxException | RuntimeException failure) {
                throw new IOException("Git worker code unavailable");
            }
        }
        var environment = new HashMap<String, String>();
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null)
            environment.put("SystemRoot", systemRoot);
        return new ProcessSpec(Path.of(System.getProperty("java.home"), "bin", "java.exe"),
            List.of("-Dfile.encoding=UTF-8", "-Djava.io.tmpdir=" + control, "-cp",
                String.join(File.pathSeparator, sources), DeploymentGitWorker.class.getName(), identity.id(),
                identity.version(), git.toString(), control.toString(), job.toString()),
            environment, checkout);
    }
}
