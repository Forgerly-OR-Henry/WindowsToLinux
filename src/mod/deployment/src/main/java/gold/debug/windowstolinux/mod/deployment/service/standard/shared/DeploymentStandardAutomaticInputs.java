package gold.debug.windowstolinux.mod.deployment.service.standard.shared;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.part.DiscoveredProjectPart;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;

/** Resolves the discovered graph before its read-only desktop review. / 在桌面只读审阅前解析已发现组件图。 */
final class DeploymentStandardAutomaticInputs {
    private DeploymentStandardAutomaticInputs() {
    }

    static Map<String, Set<gold.debug.windowstolinux.mod.deployment.service.core.model.language.SourceLanguageType>> languages(
        gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial material, List<DiscoveredProjectPart> discovered)
        throws java.io.IOException {
        var checker = new gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.core.ProjectLanguageChecker();
        var result = new LinkedHashMap<String, Set<gold.debug.windowstolinux.mod.deployment.service.core.model.language.SourceLanguageType>>();
        for (var part : discovered) {
            var relative = part.relativeRoot().normalize();
            boolean whole = relative.toString().isEmpty() || relative.toString().equals(".");
            var files = material.includedFiles().stream().map(java.nio.file.Path::of)
                .filter(path -> whole || path.startsWith(relative))
                .map(path -> whole ? path : relative.relativize(path)).toList();
            var source = new gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.source.SourceCheckFacts(
                files.size(), files, "", part.id());
            result.put(part.id(), checker.inspect(material.snapshot().directory().resolve(relative), source).sourceLanguages());
        }
        return Map.copyOf(result);
    }

    static DeploymentStandardInputs.Selection selection(String name, List<DiscoveredProjectPart> discovered,
                                                        Map<String, String> advanced) {
        var types = new LinkedHashMap<String, DeploymentProjectKind>();
        for (var part : discovered) {
            var candidates = part.types().stream().filter(DeploymentProjectKind::deployable).distinct().toList();
            if (candidates.size() != 1)
                throw new IllegalArgumentException("Cannot determine one deployment type for component " + part.id());
            types.put(part.id(), candidates.getFirst());
        }
        if (types.isEmpty()) throw new IllegalArgumentException("No deployable component was discovered");
        String generated = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-|-$", "");
        if (generated.isBlank()) generated = "application-" + UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        if (generated.length() > 63) generated = generated.substring(0, 50).replaceAll("-$", "") + "-"
            + UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString().substring(0, 8);
        String application = nonblank(advanced, "applicationId", generated);
        String front = types.keySet().stream().filter(id -> Set.of("frontend", "web").contains(id)).sorted().findFirst()
            .orElseGet(() -> types.keySet().stream().filter(id -> types.get(id) == DeploymentProjectKind.STATIC_SITE)
                .sorted().findFirst().orElse(types.keySet().stream().sorted().findFirst().orElseThrow()));
        var choices = new LinkedHashMap<String, DeploymentStandardInputs.Choice>();
        for (var part : discovered) {
            String id = part.id();
            String root = part.relativeRoot().toString().replace('\\', '/');
            choices.put(id, new DeploymentStandardInputs.Choice(types.get(id),
                id.equals(front) && types.size() > 1 ? types.keySet().stream().filter(other -> !other.equals(id))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()) : Set.of(), types.size() == 1 ? Optional.empty() : Optional.of(
                    new DeploymentStandardInputs.ComponentInputs(root.isEmpty() ? "." : root,
                        true, Optional.empty(), Optional.empty()))));
        }
        var selection = new DeploymentStandardInputs.Selection(application, choices,
            types.size() > 1 ? Optional.of(nonblank(advanced, "healthComponent", front)) : Optional.empty());
        DeploymentComponentSelection.resolve(discovered, selection);
        DeploymentStandardRuntimeDraft.validateComponents(advanced, types.keySet());
        return selection;
    }

    private static String nonblank(Map<String, String> values, String key, String fallback) {
        String value = values.getOrDefault(key, "").trim();
        return value.isEmpty() ? fallback : value;
    }
}
