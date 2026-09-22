package gold.debug.windowstolinux.shared.standard.analyze.ecosystem.java.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

/**
 * Reads the root POM and Maven entrypoint without invoking Maven or its wrapper.
 *
 *  <p>读取根 POM 和 Maven 入口，但不调用 Maven 或其 Wrapper。
 */
public final class MavenBuildInspector {
    /**
     * MAX TEXT FILE BYTES.
     * <p>最大文本文件字节。
     */
    private static final int MAX_TEXT_FILE_BYTES = 2 * 1024 * 1024;

    /**
     * Pattern recognizing ARTIFACT ID.
     * <p>用于识别制品标识的匹配模式。
     */
    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");

    /**
     * Pattern recognizing Spring Boot build plugin.
     * <p>用于识别Spring Boot 构建插件的匹配模式。
     */
    private static final Pattern BOOT_PLUGIN = Pattern
            .compile("<artifactId>\\s*spring-boot-maven-plugin\\s*</artifactId>", Pattern.CASE_INSENSITIVE);

    /**
     * Pattern recognizing WAR packaging declaration.
     * <p>用于识别WAR 打包声明的匹配模式。
     */
    private static final Pattern WAR_PACKAGING = Pattern.compile("<packaging>\\s*war\\s*</packaging>",
            Pattern.CASE_INSENSITIVE);

    /**
     * Inspects optional.
     * <p>检查可选。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param rejections reasons preventing admission to the next stage / 阻止进入下一阶段的原因
     * @return the optional operation result / 可选操作结果
     */
    public Optional<MavenBuildFacts> inspect(Path root, List<RejectionReason> rejections) {
        Path pom = root.resolve("pom.xml");
        if (!Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(root.resolve("build.gradle")) || Files.exists(root.resolve("build.gradle.kts"))) {
                rejections.add(reason("UNSUPPORTED_BUILD", "analysis.rejection.unsupportedBuild", "deployment"));
            } else {
                rejections.add(reason("MAVEN_POM_MISSING", "analysis.rejection.pomMissing", "deployment"));
            }
            return Optional.empty();
        }
        String pomText = readPom(pom, rejections);
        if (pomText == null) {
            return Optional.empty();
        }
        return Optional.of(new MavenBuildFacts(pomText, applicationName(root, pomText),
                BOOT_PLUGIN.matcher(pomText).find(), WAR_PACKAGING.matcher(pomText).find()));
    }

    /**
     * Reads bounded Maven project descriptor text or path.
     * <p>读取有界 Maven 项目描述文本或路径。
     *
     * @param pom bounded Maven project descriptor text or path / 有界 Maven 项目描述文本或路径
     * @param rejections reasons preventing admission to the next stage / 阻止进入下一阶段的原因
     * @return bounded Maven project descriptor text or path; null when no matching value is available / 有界 Maven 项目描述文本或路径；没有匹配值时为 null
     */
    private static String readPom(Path pom, List<RejectionReason> rejections) {
        try {
            if (Files.size(pom) > MAX_TEXT_FILE_BYTES) {
                rejections.add(reason("POM_TOO_LARGE", "analysis.rejection.pomTooLarge", "input"));
                return null;
            }
            return Files.readString(pom, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            rejections.add(new RejectionReason("POM_UNREADABLE",
                    LocalizedMessage.of("analysis.rejection.pomUnreadable"), "input"));
            return null;
        }
    }

    /**
     * Reads the project artifactId outside the parent declaration, falling back to the root directory name.
     * <p>读取父声明之外的项目 artifactId，缺失时使用根目录名称。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param pomText pom text / pom文本
     * @return the project artifactId outside the parent declaration, falling back to the root directory name / 父声明之外的项目 artifactId，缺失时使用根目录名称
     */
    private static String applicationName(Path root, String pomText) {
        String withoutParent = pomText.replaceAll("(?is)<parent>.*?</parent>", "");
        Matcher matcher = ARTIFACT_ID.matcher(withoutParent);
        if (matcher.find()) {
            return matcher.group(1);
        }
        Path name = root.getFileName();
        return name == null ? "application" : name.toString();
    }

    /**
     * Builds rejection reason from the supplied reason inputs.
     * <p>根据所提供原因输入构建拒绝原因。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param messageKey stable localization key for user-facing text / 用户可见文本的稳定本地化键
     * @param nextAction next action / 下一动作
     * @return rejection reason from the supplied reason inputs / 根据所提供原因输入构建拒绝原因
     */
    private static RejectionReason reason(String code, String messageKey, String nextAction) {
        return new RejectionReason(code, LocalizedMessage.of(messageKey), nextAction);
    }
}
