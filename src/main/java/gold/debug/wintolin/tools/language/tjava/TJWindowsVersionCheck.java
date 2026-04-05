package gold.debug.wintolin.tools.language.tjava;

import gold.debug.wintolin.attribute.language.AJava;
import gold.debug.wintolin.exceptionanderror.MethodUtils;
import gold.debug.wintolin.exceptionanderror.MyException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Windows 版本检查
 */
final class TJWindowsVersionCheck {

    private static final Method METHOD_CHECK =
            MethodUtils.getCurrentMethod(TJWindowsVersionCheck.class, "check", String.class);

    private static final Method METHOD_VALIDATE_WINDOWS_SYSTEM =
            MethodUtils.getCurrentMethod(TJWindowsVersionCheck.class, "validateWindowsSystem");

    private static final Method METHOD_VALIDATE_SOURCE_PATH =
            MethodUtils.getCurrentMethod(TJWindowsVersionCheck.class, "validateSourcePath", String.class);

    private static final Method METHOD_FIND_PROJECT_ROOT =
            MethodUtils.getCurrentMethod(TJWindowsVersionCheck.class, "findProjectRoot", Path.class);

    private static final Method METHOD_APPLY_PROJECT_JAVA_VERSION_FROM_POM =
            MethodUtils.getCurrentMethod(TJWindowsVersionCheck.class, "applyProjectJavaVersionFromPom", AJava.class, Path.class);

    private static final Method METHOD_PARSE_POM_JAVA_VERSION =
            MethodUtils.getCurrentMethod(TJWindowsVersionCheck.class, "parsePomJavaVersion", Path.class);

    private static final Method METHOD_GET_PROPERTY_VALUE =
            MethodUtils.getCurrentMethod(TJWindowsVersionCheck.class, "getPropertyValue", Element.class, String.class);

    private static final Method METHOD_GET_MAVEN_COMPILER_PLUGIN_VALUE =
            MethodUtils.getCurrentMethod(TJWindowsVersionCheck.class, "getMavenCompilerPluginValue", Element.class, String.class);

    private static final Method METHOD_RESOLVE_POM_PROPERTY_REFERENCE =
            MethodUtils.getCurrentMethod(TJWindowsVersionCheck.class, "resolvePomPropertyReference", Element.class, String.class);

    private static final Method METHOD_GET_DIRECT_CHILD_TEXT =
            MethodUtils.getCurrentMethod(TJWindowsVersionCheck.class, "getDirectChildText", Element.class, String.class);

    private static final Method METHOD_APPLY_MAVEN_VERSION =
            MethodUtils.getCurrentMethod(TJWindowsVersionCheck.class, "applyMavenVersion", AJava.class, Path.class);

    private static final Method METHOD_APPLY_JAVA_VERSIONS =
            MethodUtils.getCurrentMethod(TJWindowsVersionCheck.class, "applyJavaVersions", AJava.class);

    private static final Method METHOD_APPLY_COMPATIBILITY =
            MethodUtils.getCurrentMethod(TJWindowsVersionCheck.class, "applyCompatibility", AJava.class);

    private static final Method METHOD_EXECUTE_COMMAND =
            MethodUtils.getCurrentMethod(TJWindowsVersionCheck.class, "executeCommand", Path.class, String[].class);

    private static final Method METHOD_PARSE_JAVA_VERSION =
            MethodUtils.getCurrentMethod(TJWindowsVersionCheck.class, "parseJavaVersion", String.class);

    private static final Method METHOD_PARSE_JAVAC_VERSION =
            MethodUtils.getCurrentMethod(TJWindowsVersionCheck.class, "parseJavacVersion", String.class);

    private static final Method METHOD_PARSE_MAVEN_VERSION =
            MethodUtils.getCurrentMethod(TJWindowsVersionCheck.class, "parseMavenVersion", String.class);

    private static final Method METHOD_NORMALIZE_JAVA_VERSION =
            MethodUtils.getCurrentMethod(TJWindowsVersionCheck.class, "normalizeJavaVersion", String.class);

    private static final Method METHOD_TO_COMPARABLE_JAVA_MAJOR =
            MethodUtils.getCurrentMethod(TJWindowsVersionCheck.class, "toComparableJavaMajor", String.class);

    private static final Method METHOD_TRY_PARSE_INT =
            MethodUtils.getCurrentMethod(TJWindowsVersionCheck.class, "tryParseInt", String.class);

    private static final Method METHOD_FIRST_NON_BLANK =
            MethodUtils.getCurrentMethod(TJWindowsVersionCheck.class, "firstNonBlank", String.class, String.class);

    private static final Method METHOD_GET_ENV_IGNORE_BLANK =
            MethodUtils.getCurrentMethod(TJWindowsVersionCheck.class, "getEnvIgnoreBlank", String.class);

    private static final Method METHOD_IS_BLANK =
            MethodUtils.getCurrentMethod(TJWindowsVersionCheck.class, "isBlank", String.class);

    private TJWindowsVersionCheck() {
        throw new UnsupportedOperationException("TJWindowsVersionCheck is a utility class.");
    }

    static AJava check(String javaSourcePath) {
        try {
            validateWindowsSystem();

            Path sourcePath = validateSourcePath(javaSourcePath);
            Path projectRoot = findProjectRoot(sourcePath);
            Path pomPath = projectRoot == null ? null : projectRoot.resolve("pom.xml");

            AJava aJava = new AJava();

            boolean isMavenProject = pomPath != null && Files.exists(pomPath) && Files.isRegularFile(pomPath);
            aJava.setMaven(isMavenProject);

            if (isMavenProject) {
                applyProjectJavaVersionFromPom(aJava, pomPath);
                applyMavenVersion(aJava, projectRoot);
            } else {
                aJava.setProjectJavaVersion(null);
                aJava.setProjectJavaVersionSource("unknown");
                aJava.setHasMavenWrapper(false);
                aJava.setLocalMavenVersion(null);
                aJava.setLocalMavenVersionSource(null);
            }

            applyJavaVersions(aJava);
            applyCompatibility(aJava);

            return aJava;
        } catch (MyException e) {
            throw e;
        } catch (Exception e) {
            throw new MyException(
                    TJWindowsVersionCheck.class,
                    METHOD_CHECK,
                    "Windows Java 版本检查失败。",
                    "WINDOWS_JAVA_VERSION_CHECK_ERROR",
                    e
            );
        }
    }

    private static void validateWindowsSystem() {
        String osName = System.getProperty("os.name");
        if (osName == null || !osName.toLowerCase().contains("windows")) {
            throw new MyException(
                    TJWindowsVersionCheck.class,
                    METHOD_VALIDATE_WINDOWS_SYSTEM,
                    "当前系统不是 Windows，不能执行 Windows 版本检查。",
                    "OS_NOT_WINDOWS"
            );
        }
    }

    private static Path validateSourcePath(String javaSourcePath) {
        if (isBlank(javaSourcePath)) {
            throw new MyException(
                    TJWindowsVersionCheck.class,
                    METHOD_VALIDATE_SOURCE_PATH,
                    "Java 源码路径不能为空。",
                    "JAVA_SOURCE_PATH_EMPTY"
            );
        }

        try {
            Path path = Paths.get(javaSourcePath).toAbsolutePath().normalize();

            if (!Files.exists(path)) {
                throw new MyException(
                        TJWindowsVersionCheck.class,
                        METHOD_VALIDATE_SOURCE_PATH,
                        "Java 源码路径不存在：" + path,
                        "JAVA_SOURCE_PATH_NOT_EXISTS"
                );
            }

            if (!Files.isDirectory(path)) {
                throw new MyException(
                        TJWindowsVersionCheck.class,
                        METHOD_VALIDATE_SOURCE_PATH,
                        "Java 源码路径不是文件夹：" + path,
                        "JAVA_SOURCE_PATH_NOT_DIRECTORY"
                );
            }

            return path;
        } catch (MyException e) {
            throw e;
        } catch (Exception e) {
            throw new MyException(
                    TJWindowsVersionCheck.class,
                    METHOD_VALIDATE_SOURCE_PATH,
                    "Java 源码路径格式无效：" + javaSourcePath,
                    "JAVA_SOURCE_PATH_INVALID",
                    e
            );
        }
    }

    /**
     * 从当前路径向上查找项目根目录（以 pom.xml 为准）
     * 若找不到，则返回源码路径自身
     */
    private static Path findProjectRoot(Path sourcePath) {
        Path current = sourcePath;
        while (current != null) {
            Path pomPath = current.resolve("pom.xml");
            if (Files.exists(pomPath) && Files.isRegularFile(pomPath)) {
                return current;
            }
            current = current.getParent();
        }
        return sourcePath;
    }

    private static void applyProjectJavaVersionFromPom(AJava aJava, Path pomPath) {
        PomJavaVersionInfo info = parsePomJavaVersion(pomPath);
        aJava.setProjectJavaVersion(info.version);
        aJava.setProjectJavaVersionSource(info.source);
    }

    private static PomJavaVersionInfo parsePomJavaVersion(Path pomPath) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(pomPath.toFile());
            Element root = document.getDocumentElement();

            String v = getPropertyValue(root, "maven.compiler.release");
            if (!isBlank(v)) {
                return new PomJavaVersionInfo(normalizeJavaVersion(v), "pom.xml:properties.maven.compiler.release");
            }

            v = getPropertyValue(root, "java.version");
            if (!isBlank(v)) {
                return new PomJavaVersionInfo(normalizeJavaVersion(v), "pom.xml:properties.java.version");
            }

            v = getPropertyValue(root, "maven.compiler.target");
            if (!isBlank(v)) {
                return new PomJavaVersionInfo(normalizeJavaVersion(v), "pom.xml:properties.maven.compiler.target");
            }

            v = getPropertyValue(root, "maven.compiler.source");
            if (!isBlank(v)) {
                return new PomJavaVersionInfo(normalizeJavaVersion(v), "pom.xml:properties.maven.compiler.source");
            }

            v = getMavenCompilerPluginValue(root, "release");
            if (!isBlank(v)) {
                return new PomJavaVersionInfo(normalizeJavaVersion(v), "pom.xml:plugin.maven-compiler-plugin.release");
            }

            v = getMavenCompilerPluginValue(root, "target");
            if (!isBlank(v)) {
                return new PomJavaVersionInfo(normalizeJavaVersion(v), "pom.xml:plugin.maven-compiler-plugin.target");
            }

            v = getMavenCompilerPluginValue(root, "source");
            if (!isBlank(v)) {
                return new PomJavaVersionInfo(normalizeJavaVersion(v), "pom.xml:plugin.maven-compiler-plugin.source");
            }

            return new PomJavaVersionInfo(null, "unknown");
        } catch (Exception e) {
            throw new MyException(
                    TJWindowsVersionCheck.class,
                    METHOD_PARSE_POM_JAVA_VERSION,
                    "解析 pom.xml 中的 Java 版本失败：" + pomPath,
                    "POM_PARSE_ERROR",
                    e
            );
        }
    }

    private static String getPropertyValue(Element root, String propertyName) {
        NodeList propertiesList = root.getElementsByTagName("properties");
        if (propertiesList == null || propertiesList.getLength() == 0) {
            return null;
        }

        for (int i = 0; i < propertiesList.getLength(); i++) {
            if (!(propertiesList.item(i) instanceof Element propertiesElement)) {
                continue;
            }

            NodeList propertyNodes = propertiesElement.getElementsByTagName(propertyName);
            if (propertyNodes != null && propertyNodes.getLength() > 0) {
                String value = propertyNodes.item(0).getTextContent();
                if (!isBlank(value)) {
                    return resolvePomPropertyReference(root, value.trim());
                }
            }
        }

        return null;
    }

    private static String getMavenCompilerPluginValue(Element root, String tagName) {
        NodeList pluginList = root.getElementsByTagName("plugin");
        if (pluginList == null || pluginList.getLength() == 0) {
            return null;
        }

        for (int i = 0; i < pluginList.getLength(); i++) {
            if (!(pluginList.item(i) instanceof Element pluginElement)) {
                continue;
            }

            String artifactId = getDirectChildText(pluginElement, "artifactId");
            if (!"maven-compiler-plugin".equals(artifactId)) {
                continue;
            }

            NodeList configList = pluginElement.getElementsByTagName("configuration");
            if (configList == null || configList.getLength() == 0) {
                continue;
            }

            for (int j = 0; j < configList.getLength(); j++) {
                if (!(configList.item(j) instanceof Element configElement)) {
                    continue;
                }
                String value = getDirectChildText(configElement, tagName);
                if (!isBlank(value)) {
                    return resolvePomPropertyReference(root, value.trim());
                }
            }
        }

        return null;
    }

    private static String resolvePomPropertyReference(Element root, String value) {
        if (isBlank(value)) {
            return value;
        }

        String trimmed = value.trim();
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            String propertyName = trimmed.substring(2, trimmed.length() - 1).trim();
            if (!isBlank(propertyName)) {
                String resolved = getPropertyValue(root, propertyName);
                if (!isBlank(resolved) && !trimmed.equals(resolved)) {
                    return resolved;
                }
            }
        }
        return trimmed;
    }

    private static String getDirectChildText(Element parent, String tagName) {
        NodeList childNodes = parent.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            if (childNodes.item(i) instanceof Element childElement) {
                if (tagName.equals(childElement.getTagName())) {
                    String value = childElement.getTextContent();
                    return value == null ? null : value.trim();
                }
            }
        }
        return null;
    }

    private static void applyMavenVersion(AJava aJava, Path projectRoot) {
        Path mvnwCmd = projectRoot.resolve("mvnw.cmd");
        if (Files.exists(mvnwCmd) && Files.isRegularFile(mvnwCmd)) {
            aJava.setHasMavenWrapper(true);
            String version = parseMavenVersion(executeCommand(projectRoot, mvnwCmd.toString(), "-version"));
            if (!isBlank(version)) {
                aJava.setLocalMavenVersion(version);
                aJava.setLocalMavenVersionSource("MVNW");
                return;
            }
        } else {
            aJava.setHasMavenWrapper(false);
        }

        String version = parseMavenVersion(executeCommand(projectRoot, "mvn", "-version"));
        if (!isBlank(version)) {
            aJava.setLocalMavenVersion(version);
            aJava.setLocalMavenVersionSource("PATH");
            return;
        }

        String mavenHome = getEnvIgnoreBlank("MAVEN_HOME");
        if (!isBlank(mavenHome)) {
            Path mvnCmd = Paths.get(mavenHome, "bin", "mvn.cmd");
            if (Files.exists(mvnCmd) && Files.isRegularFile(mvnCmd)) {
                version = parseMavenVersion(executeCommand(projectRoot, mvnCmd.toString(), "-version"));
                if (!isBlank(version)) {
                    aJava.setLocalMavenVersion(version);
                    aJava.setLocalMavenVersionSource("MAVEN_HOME");
                    return;
                }
            }
        }

        String m2Home = getEnvIgnoreBlank("M2_HOME");
        if (!isBlank(m2Home)) {
            Path mvnCmd = Paths.get(m2Home, "bin", "mvn.cmd");
            if (Files.exists(mvnCmd) && Files.isRegularFile(mvnCmd)) {
                version = parseMavenVersion(executeCommand(projectRoot, mvnCmd.toString(), "-version"));
                if (!isBlank(version)) {
                    aJava.setLocalMavenVersion(version);
                    aJava.setLocalMavenVersionSource("M2_HOME");
                    return;
                }
            }
        }

        throw new MyException(
                TJWindowsVersionCheck.class,
                METHOD_APPLY_MAVEN_VERSION,
                "检测到当前项目为 Maven 项目，但未找到可用的 Maven 版本。检测顺序：mvnw.cmd -> PATH -> MAVEN_HOME -> M2_HOME。",
                "MAVEN_VERSION_NOT_FOUND"
        );
    }

    private static void applyJavaVersions(AJava aJava) {
        String javacVersion = parseJavacVersion(executeCommand(null, "javac", "-version"));
        String javaVersion = parseJavaVersion(executeCommand(null, "java", "-version"));

        String source = null;

        if (!isBlank(javacVersion) || !isBlank(javaVersion)) {
            source = "PATH";
        } else {
            String javaHome = getEnvIgnoreBlank("JAVA_HOME");
            if (!isBlank(javaHome)) {
                Path javacExe = Paths.get(javaHome, "bin", "javac.exe");
                Path javaExe = Paths.get(javaHome, "bin", "java.exe");

                if (Files.exists(javacExe) && Files.isRegularFile(javacExe)) {
                    javacVersion = parseJavacVersion(executeCommand(null, javacExe.toString(), "-version"));
                }
                if (Files.exists(javaExe) && Files.isRegularFile(javaExe)) {
                    javaVersion = parseJavaVersion(executeCommand(null, javaExe.toString(), "-version"));
                }

                if (!isBlank(javacVersion) || !isBlank(javaVersion)) {
                    source = "JAVA_HOME";
                }
            }
        }

        if (isBlank(javacVersion) && isBlank(javaVersion)) {
            throw new MyException(
                    TJWindowsVersionCheck.class,
                    METHOD_APPLY_JAVA_VERSIONS,
                    "未找到可用的 Java 环境。检测顺序：PATH -> JAVA_HOME。",
                    "JAVA_VERSION_NOT_FOUND"
            );
        }

        aJava.setLocalJavacVersion(javacVersion);
        aJava.setLocalJavaVersion(javaVersion);
        aJava.setLocalJavaVersionSource(source);
    }

    private static void applyCompatibility(AJava aJava) {
        String projectVersion = aJava.getProjectJavaVersion();
        String localCompileVersion = aJava.getLocalJavacVersion();
        String localRuntimeVersion = aJava.getLocalJavaVersion();

        if (isBlank(projectVersion)) {
            aJava.setVersionCompatible(true);
            aJava.setCompatibilityMessage("未从项目中解析到明确的 Java 版本要求，无法做严格比对；当前环境已检测到可用 Java。");
            return;
        }

        String localBase = firstNonBlank(localCompileVersion, localRuntimeVersion);
        if (isBlank(localBase)) {
            aJava.setVersionCompatible(false);
            aJava.setCompatibilityMessage("项目要求 Java " + projectVersion + "，但当前环境未检测到可用 Java 版本。");
            return;
        }

        Integer required = toComparableJavaMajor(projectVersion);
        Integer local = toComparableJavaMajor(localBase);

        if (required == null || local == null) {
            aJava.setVersionCompatible(false);
            aJava.setCompatibilityMessage("项目要求 Java " + projectVersion + "，当前环境为 " + localBase + "，版本格式无法比较，请人工确认。");
            return;
        }

        if (local >= required) {
            aJava.setVersionCompatible(true);
            aJava.setCompatibilityMessage("兼容：项目要求 Java " + projectVersion + "，当前环境为 " + localBase + "。");
        } else {
            aJava.setVersionCompatible(false);
            aJava.setCompatibilityMessage("不兼容：项目要求 Java " + projectVersion + "，当前环境仅为 " + localBase + "。");
        }
    }

    private static String executeCommand(Path workDir, String... command) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);

            if (workDir != null && Files.exists(workDir) && Files.isDirectory(workDir)) {
                processBuilder.directory(workDir.toFile());
            }

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

    private static String parseJavaVersion(String text) {
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

    private static String parseJavacVersion(String text) {
        if (isBlank(text)) {
            return null;
        }

        String[] lines = text.split("\\R");
        for (String line : lines) {
            String trim = line.trim().toLowerCase();
            if (trim.startsWith("javac ")) {
                return line.trim().substring("javac ".length()).trim();
            }
        }
        return null;
    }

    private static String parseMavenVersion(String text) {
        if (isBlank(text)) {
            return null;
        }

        String[] lines = text.split("\\R");
        for (String line : lines) {
            String trim = line.trim();
            if (trim.startsWith("Apache Maven ")) {
                return trim.substring("Apache Maven ".length()).trim();
            }
        }
        return null;
    }

    private static String normalizeJavaVersion(String version) {
        if (isBlank(version)) {
            return null;
        }
        return version.trim();
    }

    private static Integer toComparableJavaMajor(String version) {
        if (isBlank(version)) {
            return null;
        }

        String v = version.trim();

        if (v.startsWith("1.")) {
            int secondDot = v.indexOf('.', 2);
            if (secondDot > 2) {
                String majorPart = v.substring(2, secondDot);
                return tryParseInt(majorPart);
            }
            if (v.length() > 2) {
                return tryParseInt(v.substring(2));
            }
        }

        StringBuilder number = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (Character.isDigit(c)) {
                number.append(c);
            } else if (c == '.') {
                break;
            } else {
                break;
            }
        }

        if (number.isEmpty()) {
            return null;
        }
        return tryParseInt(number.toString());
    }

    private static Integer tryParseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (!isBlank(a)) {
            return a;
        }
        return b;
    }

    private static String getEnvIgnoreBlank(String name) {
        String value = System.getenv(name);
        return isBlank(value) ? null : value.trim();
    }

    private static boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }

    private static class PomJavaVersionInfo {
        private final String version;
        private final String source;

        private PomJavaVersionInfo(String version, String source) {
            this.version = version;
            this.source = source;
        }
    }
}