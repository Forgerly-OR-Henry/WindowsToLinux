package gold.debug.wintolin.tools.language;

import gold.debug.wintolin.attribute.language.AJava;
import gold.debug.wintolin.exceptionanderror.MyException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TJava {

    private static final String METHOD_WINDOWS_PATH_CHECK = "windowsPathCheck";

    /**
     * Windows环境检测
     *
     * 检测顺序：
     * 1. 检查源码路径是否为 Maven 项目（存在 pom.xml）
     *    - 若是，优先检查 Maven 与 Java 环境
     * 2. 若不是 Maven 项目，则检查 PATH 中的 Java
     * 3. 若 PATH 中没有，则检查 Windows 默认安装路径
     * 4. 都没有则抛出异常
     *
     * @param javaSourcePath Java源码路径
     * @return Java环境属性对象
     */
    public AJava windowsPathCheck(String javaSourcePath) {
        try {
            validateWindowsSystem();
            Path sourcePath = validateSourcePath(javaSourcePath);

            AJava aJava = new AJava();

            // 1. 优先检测 Maven 项目
            if (isMavenProject(sourcePath)) {
                aJava.setMaven(true);

                String mavenVersion = detectMavenVersionFromCommand();
                if (isBlank(mavenVersion)) {
                    throw new MyException(
                            TJava.class,
                            METHOD_WINDOWS_PATH_CHECK,
                            "检测到当前源码为 Maven 项目，但系统中未找到可用 Maven 环境。",
                            "JAVA_ENV_MAVEN_NOT_FOUND"
                    );
                }

                String jdkVersion = detectJavaVersionFromCommand();
                if (isBlank(jdkVersion)) {
                    jdkVersion = detectJavaVersionFromDefaultLocations();
                }

                if (isBlank(jdkVersion)) {
                    throw new MyException(
                            TJava.class,
                            METHOD_WINDOWS_PATH_CHECK,
                            "检测到当前源码为 Maven 项目，也检测到了 Maven，但未找到可用 Java/JDK 环境。",
                            "JAVA_ENV_JDK_NOT_FOUND_FOR_MAVEN"
                    );
                }

                aJava.setMavenVersion(mavenVersion);
                aJava.setJdkVersion(jdkVersion);
                return aJava;
            }

            // 2. 非 Maven 项目：先查 PATH
            aJava.setMaven(false);
            aJava.setMavenVersion(null);

            String jdkVersion = detectJavaVersionFromCommand();

            // 3. PATH中没有，再查默认安装目录
            if (isBlank(jdkVersion)) {
                jdkVersion = detectJavaVersionFromDefaultLocations();
            }

            if (isBlank(jdkVersion)) {
                throw new MyException(
                        TJava.class,
                        METHOD_WINDOWS_PATH_CHECK,
                        "未在 Maven、PATH 环境变量或 Java 默认安装路径中找到可用 Java/JDK 环境。",
                        "JAVA_ENV_NOT_FOUND"
                );
            }

            aJava.setJdkVersion(jdkVersion);
            return aJava;

        } catch (MyException e) {
            throw e;
        } catch (Exception e) {
            throw new MyException(
                    TJava.class,
                    METHOD_WINDOWS_PATH_CHECK,
                    "Windows Java 环境检测失败。",
                    "JAVA_ENV_CHECK_ERROR",
                    e
            );
        }
    }

    // Windows环境部署
    // 暂不实现
    public void windowsPathDeploy() {
    }

    // Linux环境检测
    // 暂不实现
    public AJava linuxPathCheck() {
        return null;
    }

    // Linux环境部署
    public void linuxPathDeploy() {
    }

    /**
     * 校验当前系统是否为 Windows
     */
    private void validateWindowsSystem() {
        String osName = System.getProperty("os.name");
        if (osName == null || !osName.toLowerCase().contains("windows")) {
            throw new MyException(
                    TJava.class,
                    METHOD_WINDOWS_PATH_CHECK,
                    "当前系统不是 Windows，不能调用 Windows 环境检测函数。",
                    "OS_NOT_WINDOWS"
            );
        }
    }

    /**
     * 校验源码路径
     */
    private Path validateSourcePath(String javaSourcePath) {
        if (isBlank(javaSourcePath)) {
            throw new MyException(
                    TJava.class,
                    METHOD_WINDOWS_PATH_CHECK,
                    "Java 源码路径不能为空。",
                    "JAVA_SOURCE_PATH_EMPTY"
            );
        }

        Path path;
        try {
            path = Paths.get(javaSourcePath).toAbsolutePath().normalize();
        } catch (Exception e) {
            throw new MyException(
                    TJava.class,
                    METHOD_WINDOWS_PATH_CHECK,
                    "Java 源码路径格式无效：" + javaSourcePath,
                    "JAVA_SOURCE_PATH_INVALID",
                    e
            );
        }

        if (!Files.exists(path)) {
            throw new MyException(
                    TJava.class,
                    METHOD_WINDOWS_PATH_CHECK,
                    "Java 源码路径不存在：" + path,
                    "JAVA_SOURCE_PATH_NOT_EXISTS"
            );
        }

        if (!Files.isDirectory(path)) {
            throw new MyException(
                    TJava.class,
                    METHOD_WINDOWS_PATH_CHECK,
                    "Java 源码路径不是文件夹：" + path,
                    "JAVA_SOURCE_PATH_NOT_DIRECTORY"
            );
        }

        return path;
    }

    /**
     * 判断是否为 Maven 项目
     * 规则：当前目录或向上查找过程中存在 pom.xml
     */
    private boolean isMavenProject(Path sourcePath) {
        Path current = sourcePath;
        while (current != null) {
            Path pomFile = current.resolve("pom.xml");
            if (Files.exists(pomFile) && Files.isRegularFile(pomFile)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    /**
     * 通过命令 java -version 检测 Java 版本
     * 依赖 PATH 环境变量
     */
    private String detectJavaVersionFromCommand() {
        String output = executeCommandAndMergeError("java", "-version");
        if (isBlank(output)) {
            return null;
        }
        return parseJavaVersion(output);
    }

    /**
     * 通过命令 mvn -version 检测 Maven 版本
     * 依赖 PATH 环境变量
     */
    private String detectMavenVersionFromCommand() {
        String output = executeCommandAndMergeError("mvn", "-version");
        if (isBlank(output)) {
            return null;
        }
        return parseMavenVersion(output);
    }

    /**
     * 从 Windows 默认安装目录中检测 Java 版本
     */
    private String detectJavaVersionFromDefaultLocations() {
        String[] javaHomes = new String[] {
                "C:\\Program Files\\Java",
                "C:\\Program Files\\Eclipse Adoptium",
                "C:\\Program Files\\Microsoft",
                "C:\\Program Files\\Amazon Corretto",
                "C:\\Program Files (x86)\\Java"
        };

        for (String home : javaHomes) {
            Path base = Paths.get(home);
            if (!Files.exists(base) || !Files.isDirectory(base)) {
                continue;
            }

            try {
                String version = searchJavaExecutableRecursively(base, 3);
                if (!isBlank(version)) {
                    return version;
                }
            } catch (Exception ignored) {
                // 单个目录失败不影响整体流程
            }
        }

        return null;
    }

    /**
     * 在指定目录下有限层级地搜索 java.exe，并尝试获取版本
     */
    private String searchJavaExecutableRecursively(Path base, int maxDepth) throws IOException {
        try (var stream = Files.walk(base, maxDepth)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> "java.exe".equalsIgnoreCase(path.getFileName().toString()))
                    .map(this::detectJavaVersionFromExecutable)
                    .filter(version -> !isBlank(version))
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * 通过指定 java.exe 的绝对路径执行 -version
     */
    private String detectJavaVersionFromExecutable(Path javaExePath) {
        String output = executeCommandAndMergeError(javaExePath.toString(), "-version");
        if (isBlank(output)) {
            return null;
        }
        return parseJavaVersion(output);
    }

    /**
     * 执行命令，并将标准输出和错误输出合并读取
     */
    private String executeCommandAndMergeError(String... command) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append(System.lineSeparator());
                }
            }

            process.waitFor();
            String result = sb.toString().trim();
            return result.isEmpty() ? null : result;

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析 java -version 输出中的版本号
     * 兼容示例：
     * java version "1.8.0_421"
     * openjdk version "21.0.2" 2024-01-16
     */
    private String parseJavaVersion(String text) {
        if (isBlank(text)) {
            return null;
        }

        String[] lines = text.split("\\R");
        for (String line : lines) {
            String lower = line.toLowerCase();
            if (lower.contains("version")) {
                int firstQuote = line.indexOf('"');
                int secondQuote = line.indexOf('"', firstQuote + 1);
                if (firstQuote >= 0 && secondQuote > firstQuote) {
                    return line.substring(firstQuote + 1, secondQuote).trim();
                }
            }
        }

        return null;
    }

    /**
     * 解析 mvn -version 输出中的 Maven 版本号
     * 兼容示例：
     * Apache Maven 3.9.6
     */
    private String parseMavenVersion(String text) {
        if (isBlank(text)) {
            return null;
        }

        String[] lines = text.split("\\R");
        for (String line : lines) {
            String trimLine = line.trim();
            if (trimLine.startsWith("Apache Maven ")) {
                return trimLine.substring("Apache Maven ".length()).trim();
            }
        }

        return null;
    }

    private boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }
}