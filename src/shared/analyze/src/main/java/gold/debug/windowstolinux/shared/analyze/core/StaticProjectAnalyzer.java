package gold.debug.windowstolinux.shared.analyze.core;

import gold.debug.windowstolinux.shared.analyze.build.maven.MavenProjectInspection;
import gold.debug.windowstolinux.shared.analyze.build.maven.MavenProjectInspector;
import gold.debug.windowstolinux.shared.analyze.framework.springboot.SpringBootProjectInspector;
import gold.debug.windowstolinux.shared.analyze.source.BoundedSourceInspector;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspection;
import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidence;
import gold.debug.windowstolinux.shared.model.analysis.ProjectAssessment;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.SourceProjectFacts;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates bounded deterministic analysis without executing project-controlled content.
 *
 * <p>在不执行项目可控内容的前提下协调有界确定性分析。
 */
public final class StaticProjectAnalyzer {
    private final MavenProjectInspector mavenInspector;
    private final BoundedSourceInspector sourceInspector;
    private final SpringBootProjectInspector springBootInspector;

    /**
     * Creates a {@code StaticProjectAnalyzer} instance.
     *
     * <p>创建 {@code StaticProjectAnalyzer} 实例。
     */
    public StaticProjectAnalyzer() {
        this(new MavenProjectInspector(), new BoundedSourceInspector(), new SpringBootProjectInspector());
    }

    StaticProjectAnalyzer(
            MavenProjectInspector mavenInspector,
            BoundedSourceInspector sourceInspector,
            SpringBootProjectInspector springBootInspector
    ) {
        this.mavenInspector = mavenInspector;
        this.sourceInspector = sourceInspector;
        this.springBootInspector = springBootInspector;
    }

    /**
     * Performs the {@code analyze} operation.
     *
     * <p>执行 {@code analyze} 操作。
     *
     * @param selectedSourceDirectory the {@code selectedSourceDirectory} value / {@code selectedSourceDirectory} 值
     * @return the operation result / 操作结果
     */
    public ProjectAssessment analyze(Path selectedSourceDirectory) {
        List<RejectionReason> rejections = new ArrayList<>();
        Path root = normalizeExistingDirectory(selectedSourceDirectory, rejections);
        if (root == null) {
            return ProjectAssessment.rejected(rejections);
        }
        MavenProjectInspection maven = mavenInspector.inspect(root, rejections).orElse(null);
        if (maven == null) {
            return ProjectAssessment.rejected(rejections);
        }
        SourceInspection source = sourceInspector.inspect(root, rejections);
        springBootInspector.inspect(maven, source, rejections);
        if (!rejections.isEmpty()) {
            return ProjectAssessment.rejected(rejections);
        }
        if (!maven.applicationName().matches("[a-z0-9][a-z0-9-]{0,62}")) {
            rejections.add(reason("APPLICATION_ID_INVALID", "analysis.rejection.applicationIdInvalid", "phase.one.input"));
            return ProjectAssessment.rejected(rejections);
        }
        boolean usableMavenWrapper = source.hasMavenWrapper()
                && Files.isRegularFile(root.resolve(".mvn/wrapper/maven-wrapper.properties"), LinkOption.NOFOLLOW_LINKS);
        List<LocalizedMessage> observations = new ArrayList<>();
        observations.add(LocalizedMessage.of("analysis.observation.staticReadCompleted"));
        observations.add(LocalizedMessage.of("analysis.observation.scannedFiles", "count", source.scannedFiles()));
        if (usableMavenWrapper) {
            observations.add(LocalizedMessage.of("analysis.observation.mavenWrapperDetected"));
        }
        String wrapperSource = usableMavenWrapper ? "mvnw + .mvn/wrapper/maven-wrapper.properties"
                : source.hasMavenWrapper() ? "mvnw"
                : source.hasWindowsMavenWrapper() ? "mvnw.cmd" : "source tree";
        LocalizedMessage wrapperConclusion = usableMavenWrapper
                ? LocalizedMessage.of("analysis.evidence.wrapper.usable")
                : source.hasMavenWrapper()
                ? LocalizedMessage.of("analysis.evidence.wrapper.configurationMissing")
                : source.hasWindowsMavenWrapper()
                ? LocalizedMessage.of("analysis.evidence.wrapper.windowsOnly")
                : LocalizedMessage.of("analysis.evidence.notDetected");
        List<AnalysisEvidence> evidence = List.of(
                new AnalysisEvidence(LocalizedMessage.of("analysis.evidence.mavenEntry"), "pom.xml",
                        LocalizedMessage.of("analysis.evidence.rootPomRead"), EvidenceConfidence.HIGH),
                new AnalysisEvidence(LocalizedMessage.of("analysis.evidence.springBootPlugin"), "pom.xml",
                        LocalizedMessage.of(maven.springBootPlugin()
                                ? "analysis.evidence.springBootPluginDetected" : "analysis.evidence.notDetected"),
                        EvidenceConfidence.HIGH),
                new AnalysisEvidence(LocalizedMessage.of("analysis.evidence.mavenWrapper"), wrapperSource,
                        wrapperConclusion, EvidenceConfidence.HIGH)
        );
        List<LocalizedMessage> missingInformation = usableMavenWrapper ? List.of()
                : source.hasMavenWrapper()
                ? List.of(LocalizedMessage.of("analysis.missing.wrapperProperties"))
                : source.hasWindowsMavenWrapper()
                ? List.of(LocalizedMessage.of("analysis.missing.windowsWrapperOnly"))
                : List.of(LocalizedMessage.of("analysis.missing.mavenRequired"));
        return ProjectAssessment.supported(new SourceProjectFacts(
                root, maven.applicationName(), usableMavenWrapper, maven.springBootPlugin(), observations, evidence,
                List.of(), missingInformation
        ));
    }

    private static Path normalizeExistingDirectory(Path source, List<RejectionReason> rejections) {
        if (source == null) {
            rejections.add(reason("SOURCE_PATH_MISSING", "analysis.rejection.sourceMissing", "phase.one.input"));
            return null;
        }
        Path normalized = source.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            rejections.add(reason("SOURCE_PATH_INVALID", "analysis.rejection.sourceInvalid", "phase.one.input"));
            return null;
        }
        return normalized;
    }

    private static RejectionReason reason(String code, String messageKey, String nextPhase) {
        return new RejectionReason(code, LocalizedMessage.of(messageKey), nextPhase);
    }
}
