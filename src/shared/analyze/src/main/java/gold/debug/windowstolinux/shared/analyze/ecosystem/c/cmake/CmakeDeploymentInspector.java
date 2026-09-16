package gold.debug.windowstolinux.shared.analyze.ecosystem.c.cmake;

import gold.debug.windowstolinux.shared.analyze.ecosystem.c.CLanguageInspector;
import gold.debug.windowstolinux.shared.analyze.service.ServiceMetadataInspector;
import gold.debug.windowstolinux.shared.analyze.source.ProjectIdentityResolver;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.analyze.contract.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.shared.analyze.contract.spi.DeploymentTypeInspector;
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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Inspects one fixed-preset, single-executable CMake service without executing CMake. / 在不执行 CMake 的情况下检查固定 preset 的单可执行服务。 */
public final class CmakeDeploymentInspector implements DeploymentTypeInspector {
    private static final String PRESET = "w2l-release";
    private static final String REVIEWED_PRESETS = "{\"version\":3,\"configurePresets\":[{\"name\":\"w2l-release\","
            + "\"generator\":\"Ninja\",\"binaryDir\":\"${sourceDir}/.w2l/cmake-build\","
            + "\"cacheVariables\":{\"CMAKE_BUILD_TYPE\":\"Release\"}}],\"buildPresets\":[{"
            + "\"name\":\"w2l-release-build\",\"configurePreset\":\"w2l-release\"}]}";
    private static final Set<String> REVIEWED_COMMANDS = Set.of(
            "add_executable", "cmake_minimum_required", "project", "target_compile_features");
    private static final Pattern COMMAND = Pattern.compile("(?i)([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern EXECUTABLE = Pattern.compile("(?im)^\\s*add_executable\\s*\\(\\s*([A-Za-z0-9][A-Za-z0-9._-]{0,127})\\s+([^\\r\\n)]+)\\)");
    private static final Pattern MINIMUM = Pattern.compile(
            "(?im)^\\s*cmake_minimum_required\\s*\\(\\s*VERSION\\s+[0-9]+(?:[.][0-9]+){1,2}\\s*\\)\\s*$");
    private static final Pattern PROJECT = Pattern.compile(
            "(?im)^\\s*project\\s*\\(\\s*[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\s+LANGUAGES\\s+([^\\r\\n)]+)\\)\\s*$");
    private static final Pattern FEATURES = Pattern.compile(
            "(?im)^\\s*target_compile_features\\s*\\(\\s*([A-Za-z0-9][A-Za-z0-9._-]{0,127})\\s+PRIVATE\\s+([^\\r\\n)]+)\\)\\s*$");

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
        String executableCmake = validateBuildDefinitions(cmake, presets, missing, conflicts);
        Matcher executable = EXECUTABLE.matcher(executableCmake);
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
            CLanguageInspector.compilationLanguage(targetSource).ifPresentOrElse(languages::add,
                    () -> conflicts.add("cmake-non-source:" + targetSource));
        }
        if (languages.isEmpty()) missing.add("C or C++ target source");
        String target = targets.size() == 1 ? targets.getFirst() : null;
        validateLanguageContract(executableCmake, target, languages, conflicts);
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

    private static String validateBuildDefinitions(String cmake, String presets, List<String> missing,
                                                   List<String> conflicts) {
        if (!presets.replaceAll("\\s+", "").equals(REVIEWED_PRESETS)) {
            conflicts.add("cmake-presets-not-exact");
        }
        String executableCmake = cmake.replaceAll("(?m)#.*$", "");
        if (executableCmake.contains("$")) conflicts.add("cmake-variable-expression");
        Map<String, Integer> commandCounts = new java.util.HashMap<>();
        Matcher commands = COMMAND.matcher(executableCmake);
        while (commands.find()) {
            String command = commands.group(1).toLowerCase(java.util.Locale.ROOT);
            commandCounts.merge(command, 1, Integer::sum);
            if (!REVIEWED_COMMANDS.contains(command)) conflicts.add("cmake-command:" + command);
        }
        REVIEWED_COMMANDS.stream().filter(command -> commandCounts.getOrDefault(command, 0) != 1)
                .forEach(command -> conflicts.add("cmake-command-count:" + command));
        if (!MINIMUM.matcher(executableCmake).find()) missing.add("cmake minimum version");
        return executableCmake;
    }

    private static void validateLanguageContract(String executableCmake, String target,
                                                 Set<SourceLanguageType> languages, List<String> conflicts) {
        Matcher project = PROJECT.matcher(executableCmake);
        List<String> projects = new ArrayList<>();
        while (project.find()) projects.add(project.group(1));
        if (projects.size() != 1 || !projectLanguages(projects, languages)) {
            conflicts.add("cmake-project-languages");
        }
        Matcher features = FEATURES.matcher(executableCmake);
        List<String> compileFeatures = new ArrayList<>();
        int featureDeclarations = 0;
        while (features.find()) {
            featureDeclarations++;
            if (target == null || !target.equals(features.group(1))) conflicts.add("cmake-feature-target");
            compileFeatures.addAll(List.of(features.group(2).trim().split("\\s+")));
        }
        if (featureDeclarations != 1 || compileFeatures.size() != languages.size()
                || languages.contains(SourceLanguageType.C) && compileFeatures.stream().filter(f -> f.matches("c_std_[0-9]+" )).count() != 1
                || languages.contains(SourceLanguageType.CPP) && compileFeatures.stream().filter(f -> f.matches("cxx_std_[0-9]+" )).count() != 1) {
            conflicts.add("cmake-compile-features");
        }
    }

    private static String relative(String value) {
        value = value.replace('\\', '/');
        return value.matches("[A-Za-z0-9._/-]{1,255}") && !value.startsWith("/") && !value.contains("..")
                && !value.contains("//") && !value.contains("$") ? value : null;
    }

    private static boolean projectLanguages(List<String> projects, Set<SourceLanguageType> languages) {
        if (projects.size() != 1) return false;
        Set<String> declared = new java.util.HashSet<>(List.of(projects.getFirst().trim().split("\\s+")));
        Set<String> expected = languages.equals(Set.of(SourceLanguageType.C)) ? Set.of("C")
                : languages.equals(Set.of(SourceLanguageType.CPP)) ? Set.of("CXX") : Set.of("C", "CXX");
        return declared.equals(expected);
    }

    private static AnalysisEvidence evidence(String source) {
        return new AnalysisEvidence(LocalizedMessage.of("analysis.cmake.metadata"), source,
                LocalizedMessage.of("analysis.deployment.evidence.detected"), EvidenceConfidenceLevel.HIGH);
    }
}
