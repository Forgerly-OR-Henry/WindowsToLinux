package gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build;

import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts;

/** Initial budgets for recognized fixed recipes, not measured dependency sizes. */
public final class DeploymentStandardWorkspaceEstimate {
    private DeploymentStandardWorkspaceEstimate() {
    }

    public static DeploymentWorkspaceQuota.Request estimate(DeploymentProjectFacts facts) {
        long unit = DeploymentWorkspaceQuota.UNIT;
        return switch (facts.buildTool()) {
            case JAVA, STATIC_SITE_BUILD, PYTHON_STDLIB, PHP_CLI, RUBY_CLI ->
                DeploymentWorkspaceQuota.Request.automatic(0, 2 * unit);
            case JDK, KOTLINC -> DeploymentWorkspaceQuota.Request.automatic(2 * unit, 8 * unit);
            case NPM, PNPM, YARN, PIP_LOCKED, PIPENV_LOCKED, POETRY_LOCKED, UV_LOCKED,
                 COMPOSER_LOCKED, BUNDLER_LOCKED -> DeploymentWorkspaceQuota.Request.automatic(8 * unit, 32 * unit);
            case MAVEN, MAVEN_WRAPPER, GRADLE_WRAPPER, GRADLE_KOTLIN_WRAPPER, GO_MODULE,
                 DOTNET_LOCKED, CMAKE -> DeploymentWorkspaceQuota.Request.automatic(16 * unit, 48 * unit);
            case CARGO_LOCKED -> DeploymentWorkspaceQuota.Request.automatic(32 * unit, 96 * unit);
            default ->
                throw new IllegalArgumentException("WORKSPACE_DEMAND_UNKNOWN: container or unclassified downloads require an explicit budget or source-backed estimate");
        };
    }
}
