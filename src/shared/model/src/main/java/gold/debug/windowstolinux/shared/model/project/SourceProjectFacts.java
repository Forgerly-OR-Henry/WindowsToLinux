package gold.debug.windowstolinux.shared.model.project;

import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, statically observed facts about a source tree.
 *
 * <p>关于源码树的不可变静态观测事实。
 *
 * @param sourceRoot the {@code sourceRoot} value / {@code sourceRoot} 值
 * @param applicationName the {@code applicationName} value / {@code applicationName} 值
 * @param usesMavenWrapper the {@code usesMavenWrapper} value / {@code usesMavenWrapper} 值
 * @param hasSpringBootPlugin the {@code hasSpringBootPlugin} value / {@code hasSpringBootPlugin} 值
 * @param observations the {@code observations} value / {@code observations} 值
 * @param evidence the {@code evidence} value / {@code evidence} 值
 * @param conflicts the {@code conflicts} value / {@code conflicts} 值
 * @param missingInformation the {@code missingInformation} value / {@code missingInformation} 值
 */
public record SourceProjectFacts(
        Path sourceRoot,
        String applicationName,
        boolean usesMavenWrapper,
        boolean hasSpringBootPlugin,
        ProjectLanguageFacts languageFacts,
        List<LocalizedMessage> observations,
        List<AnalysisEvidence> evidence,
        List<LocalizedMessage> conflicts,
        List<LocalizedMessage> missingInformation
) {
    /**
     * Creates a {@code SourceProjectFacts} instance.
     *
     * <p>创建 {@code SourceProjectFacts} 实例。
     *
     * @param sourceRoot the {@code sourceRoot} value / {@code sourceRoot} 值
     * @param applicationName the {@code applicationName} value / {@code applicationName} 值
     * @param usesMavenWrapper the {@code usesMavenWrapper} value / {@code usesMavenWrapper} 值
     * @param hasSpringBootPlugin the {@code hasSpringBootPlugin} value / {@code hasSpringBootPlugin} 值
     * @param observations the {@code observations} value / {@code observations} 值
     * @param evidence the {@code evidence} value / {@code evidence} 值
     * @param conflicts the {@code conflicts} value / {@code conflicts} 值
     * @param missingInformation the {@code missingInformation} value / {@code missingInformation} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public SourceProjectFacts {
        sourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot").toAbsolutePath().normalize();
        applicationName = requireText(applicationName, "applicationName");
        languageFacts = Objects.requireNonNull(languageFacts, "languageFacts");
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        conflicts = List.copyOf(Objects.requireNonNull(conflicts, "conflicts"));
        missingInformation = List.copyOf(Objects.requireNonNull(missingInformation, "missingInformation"));
    }

    /**
     * Creates a {@code SourceProjectFacts} instance.
     *
     * <p>创建 {@code SourceProjectFacts} 实例。
     *
     * @param sourceRoot the {@code sourceRoot} value / {@code sourceRoot} 值
     * @param applicationName the {@code applicationName} value / {@code applicationName} 值
     * @param usesMavenWrapper the {@code usesMavenWrapper} value / {@code usesMavenWrapper} 值
     * @param hasSpringBootPlugin the {@code hasSpringBootPlugin} value / {@code hasSpringBootPlugin} 值
     * @param observations the {@code observations} value / {@code observations} 值
     */
    public SourceProjectFacts(Path sourceRoot, String applicationName, boolean usesMavenWrapper,
                              boolean hasSpringBootPlugin, List<LocalizedMessage> observations) {
        this(sourceRoot, applicationName, usesMavenWrapper, hasSpringBootPlugin, ProjectLanguageFacts.empty(), observations,
                List.of(), List.of(), List.of());
    }

    /** Creates facts with legacy call-site fields and no language observations. / 使用原调用字段且不含语言观测创建事实。 */
    public SourceProjectFacts(Path sourceRoot, String applicationName, boolean usesMavenWrapper,
                              boolean hasSpringBootPlugin, List<LocalizedMessage> observations,
                              List<AnalysisEvidence> evidence, List<LocalizedMessage> conflicts,
                              List<LocalizedMessage> missingInformation) {
        this(sourceRoot, applicationName, usesMavenWrapper, hasSpringBootPlugin, ProjectLanguageFacts.empty(), observations,
                evidence, conflicts, missingInformation);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
