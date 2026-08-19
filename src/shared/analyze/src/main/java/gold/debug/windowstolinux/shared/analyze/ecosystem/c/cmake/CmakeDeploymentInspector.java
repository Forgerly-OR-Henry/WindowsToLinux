package gold.debug.windowstolinux.shared.analyze.ecosystem.c.cmake;

import gold.debug.windowstolinux.shared.analyze.service.ServiceMetadataInspector;
import gold.debug.windowstolinux.shared.analyze.source.ProjectIdentityResolver;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.analyze.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.shared.analyze.spi.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidenceLevel;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeAssessment;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Inspects one fixed-preset, single-executable CMake service without executing CMake. / 在不执行 CMake 的情况下检查固定 preset 的单可执行服务。 */
public final class CmakeDeploymentInspector implements DeploymentTypeInspector {
    private static final String PRESET = "w2l-release";
    private static final Pattern EXECUTABLE = Pattern.compile("(?im)^\\s*add_executable\\s*\\(\\s*([A-Za-z0-9][A-Za-z0-9._-]{0,127})\\s+([^\\r\\n)]+)\\)");
    private static final Pattern UNSAFE = Pattern.compile(
            "(?i)\\b(?:FetchContent(?:_Declare|_MakeAvailable)?|ExternalProject_Add|file\\s*\\(\\s*DOWNLOAD|execute_process|add_custom_command|add_custom_target|install)\\s*\\(");

    /** Returns the CMake service type. / 返回 CMake 服务类型。 */
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.CMAKE_SERVICE; }

    /** Inspects fixed presets, one executable target, exact sources, and unsafe declarations. / 检查固定 preset、单一可执行目标、精确源码与不安全声明。 */
    @Override
    public DeploymentTypeAssessment inspect(Path root, SourceInspectionFacts source, ProjectLanguageFacts languageFacts,
                                            List<RejectionReason> rejections) throws IOException {
        List<String> missing = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        String cmake = ServiceMetadataInspector.readIfPresent(root.resolve("CMakeLists.txt"));
        String presets = ServiceMetadataInspector.readIfPresent(root.resolve("CMakePresets.json"));
        if (cmake.isEmpty()) missing.add("CMakeLists.txt");
        if (presets.isEmpty()) missing.add("CMakePresets.json");
        if (!presets.contains("\"name\": \"" + PRESET + "\"")
                && !presets.contains("\"name\":\"" + PRESET + "\"")) missing.add("configure preset " + PRESET);
        if (!presets.contains("\"name\": \"" + PRESET + "-build\"")
                && !presets.contains("\"name\":\"" + PRESET + "-build\"")) missing.add("build preset " + PRESET + "-build");
        if (!presets.contains("\"generator\": \"Ninja\"")
                && !presets.contains("\"generator\":\"Ninja\"")) missing.add("Ninja generator");
        if (!presets.contains("${sourceDir}/.w2l/cmake-build")) missing.add("fixed CMake binaryDir");
        if (UNSAFE.matcher(cmake).find()) conflicts.add("cmake-unsafe-command");
        Matcher executable = EXECUTABLE.matcher(cmake);
        List<String> targets = new ArrayList<>();
        List<String> targetSources = new ArrayList<>();
        while (executable.find()) {
            targets.add(executable.group(1));
            for (String token : executable.group(2).trim().split("\\s+")) {
                String normalized = relative(token);
                if (normalized == null) conflicts.add("cmake-target-source:" + token);
                else targetSources.add(normalized);
            }
        }
        if (targets.size() != 1) conflicts.add("cmake-executable-count:" + targets.size());
        EnumSet<SourceLanguageType> languages = EnumSet.noneOf(SourceLanguageType.class);
        for (String targetSource : targetSources) {
            if (!ServiceMetadataInspector.present(root, targetSource)) missing.add(targetSource);
            String lower = targetSource.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".c")) languages.add(SourceLanguageType.C);
            else if (lower.endsWith(".cc") || lower.endsWith(".cpp") || lower.endsWith(".cxx")) languages.add(SourceLanguageType.CPP);
            else conflicts.add("cmake-non-source:" + targetSource);
        }
        if (languages.isEmpty()) missing.add("C or C++ target source");
        if (languages.contains(SourceLanguageType.C) && !cmake.contains("c_std_17")) missing.add("c_std_17");
        if (languages.contains(SourceLanguageType.CPP) && !cmake.contains("cxx_std_20")) missing.add("cxx_std_20");
        String target = targets.size() == 1 ? targets.getFirst() : null;
        CmakeFacts architecture = new CmakeFacts(PRESET, target, languages, targetSources, missing, conflicts);
        ProjectLanguageFacts targetLanguageFacts = new ProjectLanguageFacts(languageFacts.ecosystems(), languages,
                languageFacts.values(), languageFacts.evidence());
        List<LocalizedMessage> localizedMissing = architecture.missingItems().stream()
                .map(item -> LocalizedMessage.of("analysis.service.missingFile", "file", item)).toList();
        List<LocalizedMessage> localizedConflicts = architecture.conflicts().stream()
                .map(item -> LocalizedMessage.of("analysis.cmake.conflict", "detail", item)).toList();
        DeploymentProjectFacts facts = new DeploymentProjectFacts(root, ProjectIdentityResolver.rootApplicationId(root),
                projectType(), DeploymentBuildToolType.CMAKE, targetLanguageFacts,
                List.of(evidence("CMakeLists.txt"), evidence("CMakePresets.json")), localizedConflicts, localizedMissing);
        Map<DeploymentRuntimeAssessment.RuntimeInputType, String> values =
                new EnumMap<>(DeploymentRuntimeAssessment.RuntimeInputType.class);
        values.put(DeploymentRuntimeAssessment.RuntimeInputType.CMAKE_PRESET, PRESET);
        if (target != null) {
            values.put(DeploymentRuntimeAssessment.RuntimeInputType.CMAKE_TARGET, target);
            values.put(DeploymentRuntimeAssessment.RuntimeInputType.CMAKE_ARTIFACT, target);
        }
        List<LocalizedMessage> required = new ArrayList<>(List.of(LocalizedMessage.of("analysis.deployment.runtime.health")));
        if (target == null) required.add(LocalizedMessage.of("analysis.cmake.target"));
        return new DeploymentTypeAssessment(facts, new DeploymentRuntimeAssessment(projectType(), values,
                Optional.empty(), Map.of(), List.of(), facts.evidence(), required));
    }

    private static String relative(String value) {
        value = value.replace('\\', '/');
        return value.matches("[A-Za-z0-9._/-]{1,255}") && !value.startsWith("/") && !value.contains("..")
                && !value.contains("//") && !value.contains("$") ? value : null;
    }

    private static AnalysisEvidence evidence(String source) {
        return new AnalysisEvidence(LocalizedMessage.of("analysis.cmake.metadata"), source,
                LocalizedMessage.of("analysis.deployment.evidence.detected"), EvidenceConfidenceLevel.HIGH);
    }
}
