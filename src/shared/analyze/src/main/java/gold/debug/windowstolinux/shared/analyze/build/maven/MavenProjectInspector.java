package gold.debug.windowstolinux.shared.analyze.build.maven;

import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the root POM and Maven entrypoint without invoking Maven or its wrapper.
 *
 * <p>读取根 POM 和 Maven 入口，但不调用 Maven 或其 Wrapper。
 */
public final class MavenProjectInspector {
    private static final int MAX_TEXT_FILE_BYTES = 2 * 1024 * 1024;
    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");
    private static final Pattern BOOT_PLUGIN = Pattern.compile(
            "<artifactId>\\s*spring-boot-maven-plugin\\s*</artifactId>", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern WAR_PACKAGING = Pattern.compile(
            "<packaging>\\s*war\\s*</packaging>", Pattern.CASE_INSENSITIVE
    );

    /**
     * Performs the {@code inspect} operation.
     *
     * <p>执行 {@code inspect} 操作。
     *
     * @param root the {@code root} value / {@code root} 值
     * @param rejections the {@code rejections} value / {@code rejections} 值
     * @return the optional operation result / 可选操作结果
     */
    public Optional<MavenProjectInspection> inspect(Path root, List<RejectionReason> rejections) {
        Path pom = root.resolve("pom.xml");
        if (!Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(root.resolve("build.gradle")) || Files.exists(root.resolve("build.gradle.kts"))) {
                rejections.add(reason("UNSUPPORTED_BUILD", "analysis.rejection.unsupportedBuild", "phase.two"));
            } else {
                rejections.add(reason("MAVEN_POM_MISSING", "analysis.rejection.pomMissing", "phase.two"));
            }
            return Optional.empty();
        }
        String pomText = readPom(pom, rejections);
        if (pomText == null) {
            return Optional.empty();
        }
        return Optional.of(new MavenProjectInspection(pomText, applicationName(root, pomText),
                BOOT_PLUGIN.matcher(pomText).find(), WAR_PACKAGING.matcher(pomText).find()));
    }

    private static String readPom(Path pom, List<RejectionReason> rejections) {
        try {
            if (Files.size(pom) > MAX_TEXT_FILE_BYTES) {
                rejections.add(reason("POM_TOO_LARGE", "analysis.rejection.pomTooLarge", "phase.one.input"));
                return null;
            }
            return Files.readString(pom, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            rejections.add(new RejectionReason("POM_UNREADABLE",
                    LocalizedMessage.of("analysis.rejection.pomUnreadable",
                            java.util.Map.of("detail", String.valueOf(exception.getMessage()))), "phase.one.input"));
            return null;
        }
    }

    private static String applicationName(Path root, String pomText) {
        String withoutParent = pomText.replaceAll("(?is)<parent>.*?</parent>", "");
        Matcher matcher = ARTIFACT_ID.matcher(withoutParent);
        if (matcher.find()) {
            return matcher.group(1);
        }
        Path name = root.getFileName();
        return name == null ? "application" : name.toString();
    }

    private static RejectionReason reason(String code, String messageKey, String nextPhase) {
        return new RejectionReason(code, LocalizedMessage.of(messageKey), nextPhase);
    }
}
