package gold.debug.windowstolinux.shared.model.project.application;

import java.util.Objects;

/** Build-only unit delivered atomically with its owning application. / 随所属应用原子交付的仅构建单元。 */
public record ApplicationCompanion(String id, String sourcePath, BuildType projectType,
                                   String artifactPath, String environment) {
    public enum BuildType { CMAKE_SERVICE }

    public ApplicationCompanion {
        if (!Objects.requireNonNull(id).matches("[a-z0-9][a-z0-9-]{0,62}"))
            throw new IllegalArgumentException("invalid companion identifier");
        sourcePath = ApplicationCommand.relative(sourcePath, false);
        artifactPath = ApplicationCommand.relative(artifactPath, false);
        Objects.requireNonNull(projectType);
        if (!Objects.requireNonNull(environment).matches("[A-Z][A-Z0-9_]{0,63}")
                || environment.startsWith("LD_") || environment.startsWith("PYTHON")
                || environment.startsWith("RUBY") || environment.startsWith("GEM_")
                || environment.startsWith("BUNDLE_") || environment.startsWith("JAVA")
                || environment.startsWith("NODE_") || environment.startsWith("WINDOWSTOLINUX_")
                || java.util.Set.of("PATH", "HOME", "SHELL", "ENV", "BASH_ENV", "TMPDIR").contains(environment))
            throw new IllegalArgumentException("companion requires a path environment binding");
    }
}
