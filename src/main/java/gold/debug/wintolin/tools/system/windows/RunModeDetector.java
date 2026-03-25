package gold.debug.wintolin.tools.system.windows;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class RunModeDetector {

    public enum RunMode { RUN_CLASS, RUN_JAR, RUN_APP }

    private RunModeDetector() {}

    /**
     * 检测运行模式：RUN_CLASS / RUN_JAR / RUN_APP
     *
     * @param anchorClass 建议传入你项目里某个稳定存在的类（比如 AppMain.class）
     */
    public static RunMode detect(Class<?> anchorClass) {
        // 1) 先根据 CodeSource 判断 class 目录 or jar
        Path codeSourcePath = getCodeSourcePath(anchorClass);
        if (codeSourcePath != null) {
            String p = codeSourcePath.toString().toLowerCase();

            // 目录 -> 多数情况是运行 class（IDEA / Maven / Gradle 输出目录）
            if (isDirectory(codeSourcePath)) {
                return RunMode.RUN_CLASS;
            }

            // jar -> 再区分：普通 java -jar 还是 jpackage 内 app/xxx.jar
            if (p.endsWith(".jar")) {
                // jpackage 常见：<app-image>/app/xxx.jar
                if (looksLikeJPackageAppJar(codeSourcePath)) {
                    return RunMode.RUN_APP;
                }
                return RunMode.RUN_JAR;
            }
        }

        // 2) CodeSource 拿不到时（少见），用环境特征兜底
        if (looksLikeJPackageByRuntimeLayout()) {
            return RunMode.RUN_APP;
        }

        // 兜底：更倾向于 class（因为 jar 一般能拿到 CodeSource）
        return RunMode.RUN_CLASS;
    }

    private static Path getCodeSourcePath(Class<?> anchorClass) {
        try {
            URL location = anchorClass.getProtectionDomain()
                    .getCodeSource()
                    .getLocation();
            if (location == null) return null;
            URI uri = location.toURI();
            return Paths.get(uri).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isDirectory(Path p) {
        try {
            return p.toFile().isDirectory();
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 判断当前 jar 路径是否像 jpackage 的 app/xxx.jar
     */
    private static boolean looksLikeJPackageAppJar(Path jarPath) {
        try {
            Path parent = jarPath.getParent();
            if (parent == null) return false;
            String parentName = parent.getFileName().toString().toLowerCase();
            if (!parentName.equals("app")) return false;

            Path appImageRoot = parent.getParent(); // <app-image>/
            if (appImageRoot == null) return false;

            // jpackage app-image 典型结构：app/ + runtime/
            Path runtimeDir = appImageRoot.resolve("runtime");
            if (runtimeDir.toFile().exists() && runtimeDir.toFile().isDirectory()) {
                return true;
            }

            // macOS 常见：YourApp.app/Contents/runtime 和 Contents/app
            // 但 jarPath 一般会在 Contents/app/xxx.jar
            Path contents = parent.getParent();
            if (contents != null && contents.getFileName().toString().equalsIgnoreCase("Contents")) {
                Path runtime2 = contents.resolve("runtime");
                if (runtime2.toFile().exists() && runtime2.toFile().isDirectory()) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * 兜底：从工作目录/启动目录推断是否 jpackage app-image。
     * 适合你“把 config 放到 runtime 和 app 同级目录”的场景。
     */
    private static boolean looksLikeJPackageByRuntimeLayout() {
        try {
            Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
            // 常见：<app-image>/bin 启动，cwd 可能是 <app-image> 或 <app-image>/bin
            if (hasJPackageLayout(cwd)) return true;
            Path parent = cwd.getParent();
            return parent != null && hasJPackageLayout(parent);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasJPackageLayout(Path root) {
        File app = root.resolve("app").toFile();
        File runtime = root.resolve("runtime").toFile();
        File bin = root.resolve("bin").toFile();
        // jpackage app-image：一般至少有 app + runtime + bin（不同平台可能略差异，但通常都在）
        int hit = 0;
        if (app.exists() && app.isDirectory()) hit++;
        if (runtime.exists() && runtime.isDirectory()) hit++;
        if (bin.exists() && bin.isDirectory()) hit++;
        return hit >= 2; // 命中 2 个就认为很像
    }
}
