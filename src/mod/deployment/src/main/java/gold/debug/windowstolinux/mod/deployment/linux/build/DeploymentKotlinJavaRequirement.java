package gold.debug.windowstolinux.mod.deployment.linux.build;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainVersion;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement;

/** Reviewed Java constraint and the exact release selected before the current Kotlin preparation. */
public record DeploymentKotlinJavaRequirement(ToolchainRequirement.ConstraintType constraint,
                                              String version, String selectedVersion) {
    public DeploymentKotlinJavaRequirement {
        var expected = canonical(version);
        var selected = canonical(selectedVersion);
        var required = new ToolchainRequirement(DeploymentToolchainType.JAVA, version, "Kotlin preparation",
            ToolchainRequirement.PurposeType.BUILD, constraint, Optional.of(expected));
        if (!required.accepts(selected)) throw invalid();
    }

    public static DeploymentKotlinJavaRequirement from(DeploymentResolvedToolchains.Selection java) {
        var requirement = java.requirement();
        return new DeploymentKotlinJavaRequirement(requirement.constraint(),
            requirement.version().orElseThrow().text(), java.version().text());
    }

    public Map<String, String> facts() {
        return Map.of("constraint", constraint.name(), "version", version, "selectedVersion", selectedVersion);
    }

    static DeploymentKotlinJavaRequirement decode(JsonNode value) {
        if (value == null) return null;
        if (!value.isObject() || value.size() != 3
            || Set.of("constraint", "version", "selectedVersion").stream().anyMatch(field -> !value.path(field).isTextual()))
            throw invalid();
        return new DeploymentKotlinJavaRequirement(ToolchainRequirement.ConstraintType.valueOf(value.path("constraint").textValue()),
            value.path("version").textValue(), value.path("selectedVersion").textValue());
    }

    static void verifyApplicability(String ecosystem, String javaHome, DeploymentKotlinJavaRequirement requirement) {
        if (requirement != null && (!"KOTLIN".equals(ecosystem) || "-".equals(javaHome))) throw invalid();
    }

    static void requireForExecution(String ecosystem, DeploymentKotlinJavaRequirement requirement) {
        if ("KOTLIN".equals(ecosystem) && requirement == null) throw invalid();
    }

    private static DeploymentToolchainVersion canonical(String value) {
        return DeploymentToolchainVersion.parse(DeploymentToolchainType.JAVA, value)
            .filter(parsed -> value.equals(parsed.text()) && !parsed.preview()).orElseThrow(DeploymentKotlinJavaRequirement::invalid);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid planned Java prerequisite for Kotlin preparation");
    }
}
