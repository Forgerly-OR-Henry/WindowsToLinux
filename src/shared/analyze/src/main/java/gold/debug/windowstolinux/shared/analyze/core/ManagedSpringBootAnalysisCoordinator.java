package gold.debug.windowstolinux.shared.analyze.core;

import gold.debug.windowstolinux.shared.analyze.build.maven.MavenProjectInspection;
import gold.debug.windowstolinux.shared.analyze.build.maven.MavenProjectInspector;
import gold.debug.windowstolinux.shared.analyze.framework.springboot.SpringBootProjectInspector;
import gold.debug.windowstolinux.shared.analyze.language.ProjectLanguageInspector;
import gold.debug.windowstolinux.shared.analyze.source.BoundedSourceInspector;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspection;
import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidence;
import gold.debug.windowstolinux.shared.model.analysis.ProjectAssessment;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.project.SourceProjectFacts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates the managed Maven Spring Boot analysis while delegated inspectors own all facts.
 *
 * <p>协调受管 Maven Spring Boot 分析，各项事实由已委托的检查器负责。
 */
public final class ManagedSpringBootAnalysisCoordinator {
    private final MavenProjectInspector mavenInspector = new MavenProjectInspector();
    private final BoundedSourceInspector sourceInspector = new BoundedSourceInspector();
    private final SpringBootProjectInspector springBootInspector = new SpringBootProjectInspector();
    private final ProjectLanguageInspector languageInspector = new ProjectLanguageInspector();

    /** Performs bounded managed Spring Boot analysis. / 执行有界受管 Spring Boot 分析。 */
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
            return ProjectAssessment.rejected(List.of(reason("APPLICATION_ID_INVALID",
                    "analysis.rejection.applicationIdInvalid", "input")));
        }
        ProjectLanguageFacts languageFacts;
        try {
            languageFacts = languageInspector.inspect(root, source);
        } catch (IOException exception) {
            return ProjectAssessment.rejected(List.of(reason("SOURCE_READ_FAILED",
                    "analysis.rejection.sourceReadFailed", "input")));
        }
        boolean usableWrapper = source.hasMavenWrapper()
                && Files.isRegularFile(root.resolve(".mvn/wrapper/maven-wrapper.properties"), LinkOption.NOFOLLOW_LINKS);
        List<LocalizedMessage> observations = new ArrayList<>();
        observations.add(LocalizedMessage.of("analysis.observation.staticReadCompleted"));
        observations.add(LocalizedMessage.of("analysis.observation.scannedFiles", "count", source.scannedFiles()));
        if (usableWrapper) {
            observations.add(LocalizedMessage.of("analysis.observation.mavenWrapperDetected"));
        }
        String wrapperSource = usableWrapper ? "mvnw + .mvn/wrapper/maven-wrapper.properties"
                : source.hasMavenWrapper() ? "mvnw" : source.hasWindowsMavenWrapper() ? "mvnw.cmd" : "source tree";
        LocalizedMessage wrapperConclusion = usableWrapper ? LocalizedMessage.of("analysis.evidence.wrapper.usable")
                : source.hasMavenWrapper() ? LocalizedMessage.of("analysis.evidence.wrapper.configurationMissing")
                : source.hasWindowsMavenWrapper() ? LocalizedMessage.of("analysis.evidence.wrapper.windowsOnly")
                : LocalizedMessage.of("analysis.evidence.notDetected");
        List<AnalysisEvidence> evidence = List.of(
                new AnalysisEvidence(LocalizedMessage.of("analysis.evidence.mavenEntry"), "pom.xml",
                        LocalizedMessage.of("analysis.evidence.rootPomRead"), EvidenceConfidence.HIGH),
                new AnalysisEvidence(LocalizedMessage.of("analysis.evidence.springBootPlugin"), "pom.xml",
                        LocalizedMessage.of(maven.springBootPlugin() ? "analysis.evidence.springBootPluginDetected"
                                : "analysis.evidence.notDetected"), EvidenceConfidence.HIGH),
                new AnalysisEvidence(LocalizedMessage.of("analysis.evidence.mavenWrapper"), wrapperSource,
                        wrapperConclusion, EvidenceConfidence.HIGH));
        List<LocalizedMessage> missing = usableWrapper ? List.of() : source.hasMavenWrapper()
                ? List.of(LocalizedMessage.of("analysis.missing.wrapperProperties")) : source.hasWindowsMavenWrapper()
                ? List.of(LocalizedMessage.of("analysis.missing.windowsWrapperOnly"))
                : List.of(LocalizedMessage.of("analysis.missing.mavenRequired"));
        return ProjectAssessment.supported(new SourceProjectFacts(root, maven.applicationName(), usableWrapper,
                maven.springBootPlugin(), languageFacts, observations, evidence, List.of(), missing));
    }

    private static Path normalizeExistingDirectory(Path source, List<RejectionReason> rejections) {
        if (source == null) {
            rejections.add(reason("SOURCE_PATH_MISSING", "analysis.rejection.sourceMissing", "input"));
            return null;
        }
        Path normalized = source.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            rejections.add(reason("SOURCE_PATH_INVALID", "analysis.rejection.sourceInvalid", "input"));
            return null;
        }
        return normalized;
    }

    private static RejectionReason reason(String code, String key, String nextAction) {
        return new RejectionReason(code, LocalizedMessage.of(key), nextAction);
    }
}
