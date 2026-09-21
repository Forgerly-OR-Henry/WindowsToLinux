package gold.debug.windowstolinux.shared.model.project.application;

import java.util.Objects;

/**
 * Build-only unit delivered atomically with its owning application. / 随所属应用原子交付的仅构建单元。
 *
 * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
 * @param sourcePath source path / 源码路径
 * @param projectType supported project deployment category / 受支持的项目部署类别
 * @param artifactPath artifact path / 制品路径
 * @param environment environment / 环境
 */
public record ApplicationCompanion(String id, String sourcePath, BuildType projectType,
                                   String artifactPath, String environment) {
    /**
     * Selects the supported build contract for a companion artifact.
     * <p>选择配套制品支持的构建契约。
     */
    public enum BuildType {
    /**
     * CMAKE SERVICE classification within build type.
     * <p>构建类型中的CMAKE服务分类。
     */
     CMAKE_SERVICE }

    /**
     * Validates and binds the inputs required by application companion.
     * <p>校验并绑定应用配套单元所需输入。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param sourcePath source path / 源码路径
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @param artifactPath artifact path / 制品路径
     * @param environment environment / 环境
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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
