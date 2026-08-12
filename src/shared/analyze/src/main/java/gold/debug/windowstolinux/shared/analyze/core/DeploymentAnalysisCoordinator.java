package gold.debug.windowstolinux.shared.analyze.core;

import gold.debug.windowstolinux.shared.analyze.build.node.NodeServiceDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.build.python.PythonServiceDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.framework.springboot.SpringBootDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.language.ProjectLanguageInspector;
import gold.debug.windowstolinux.shared.analyze.language.java.JavaJarDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.source.BoundedSourceInspector;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspection;
import gold.debug.windowstolinux.shared.analyze.workload.container.ContainerDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.workload.staticweb.StaticWebDeploymentInspector;
import gold.debug.windowstolinux.shared.model.analysis.DeploymentProjectAssessment;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.ProjectLanguageFacts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Coordinates bounded traversal, safety policy, type dispatch, and result aggregation only.
 *
 * <p>仅协调有界遍历、安全策略、类型分派与结果汇总。
 */
public final class DeploymentAnalysisCoordinator {
    private static final Pattern DATABASE_MIGRATION = Pattern.compile(
            "\\b(flyway|liquibase|alembic|prisma(?:\\s+migrate)?|knex)\\b", Pattern.CASE_INSENSITIVE);
    private final BoundedSourceInspector sourceInspector;
    private final ProjectLanguageInspector languageInspector;
    private final Map<DeploymentProjectType, DeploymentTypeInspector> inspectors;

    /** Creates a coordinator with exactly one inspector for every supported type. / 为每种支持类型各配置一个检查器。 */
    public DeploymentAnalysisCoordinator() {
        this(new BoundedSourceInspector(), new ProjectLanguageInspector(), List.of(
                new SpringBootDeploymentInspector(), new JavaJarDeploymentInspector(),
                new NodeServiceDeploymentInspector(), new PythonServiceDeploymentInspector(),
                new StaticWebDeploymentInspector(), new ContainerDeploymentInspector()));
    }

    DeploymentAnalysisCoordinator(BoundedSourceInspector sourceInspector, ProjectLanguageInspector languageInspector,
                                  List<DeploymentTypeInspector> inspectors) {
        this.sourceInspector = Objects.requireNonNull(sourceInspector, "sourceInspector");
        this.languageInspector = Objects.requireNonNull(languageInspector, "languageInspector");
        EnumMap<DeploymentProjectType, DeploymentTypeInspector> indexed = new EnumMap<>(DeploymentProjectType.class);
        for (DeploymentTypeInspector inspector : Objects.requireNonNull(inspectors, "inspectors")) {
            DeploymentTypeInspector previous = indexed.put(inspector.projectType(), inspector);
            if (previous != null) {
                throw new IllegalArgumentException("each deployment type requires exactly one inspector");
            }
        }
        if (indexed.size() != DeploymentProjectType.values().length) {
            throw new IllegalArgumentException("every deployment type requires an inspector");
        }
        this.inspectors = Map.copyOf(indexed);
    }

    /** Analyzes one explicitly selected type without executing source. / 在不执行源码的情况下分析一个显式选择的类型。 */
    public DeploymentProjectAssessment analyze(Path selectedSourceDirectory, DeploymentProjectType projectType) {
        List<RejectionReason> rejections = new ArrayList<>();
        Path root = normalizeRoot(selectedSourceDirectory, rejections);
        if (root == null) {
            return DeploymentProjectAssessment.rejected(rejections);
        }
        SourceInspection source = sourceInspector.inspect(root, rejections);
        if (source.hasDatabaseChangeScript()) {
            rejections.add(rejection("AUTOMATIC_SCHEMA_MUTATION_DETECTED", "analysis.rejection.schemaMutationDetected"));
        }
        if (DATABASE_MIGRATION.matcher(source.scannedText()).find()) {
            rejections.add(rejection("DATABASE_MIGRATION_DETECTED", "analysis.rejection.migrationDetected"));
        }
        if (!rejections.isEmpty()) {
            return DeploymentProjectAssessment.rejected(rejections);
        }
        try {
            ProjectLanguageFacts languageFacts = languageInspector.inspect(root, source);
            DeploymentTypeInspection inspected = inspectors.get(Objects.requireNonNull(projectType, "projectType"))
                    .inspect(root, source, languageFacts, rejections);
            if (inspected == null || !rejections.isEmpty()) {
                return DeploymentProjectAssessment.rejected(rejections);
            }
            return inspected.facts().readyForPlanning()
                    ? DeploymentProjectAssessment.ready(inspected.facts(), inspected.runtimeSuggestion())
                    : DeploymentProjectAssessment.requiresInput(inspected.facts(), inspected.runtimeSuggestion());
        } catch (IOException exception) {
            return DeploymentProjectAssessment.rejected(List.of(rejection("DEPLOYMENT_SOURCE_READ_FAILED",
                    "analysis.deployment.rejection.sourceReadFailed")));
        }
    }

    private static Path normalizeRoot(Path source, List<RejectionReason> rejections) {
        if (source == null) {
            rejections.add(rejection("SOURCE_PATH_MISSING", "analysis.deployment.rejection.sourceMissing"));
            return null;
        }
        Path normalized = source.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            rejections.add(rejection("SOURCE_PATH_INVALID", "analysis.deployment.rejection.sourceInvalid"));
            return null;
        }
        return normalized;
    }

    private static RejectionReason rejection(String code, String key) {
        return new RejectionReason(code, LocalizedMessage.of(key), "deployment");
    }
}
