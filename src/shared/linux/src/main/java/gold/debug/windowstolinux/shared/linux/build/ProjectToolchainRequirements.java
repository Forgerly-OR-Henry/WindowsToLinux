package gold.debug.windowstolinux.shared.linux.build;

import gold.debug.windowstolinux.shared.model.toolchain.*;

import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import java.util.ArrayList;
import java.util.List;
import static gold.debug.windowstolinux.shared.model.toolchain.ToolchainEcosystemType.*;
import static gold.debug.windowstolinux.shared.model.toolchain.ToolchainRequirement.PurposeType.*;

/** Shared projection of reviewed runtime inputs and original declarations. / 审阅输入与原始声明的公共工具链需求投影。 */
public final class ProjectToolchainRequirements {
    private ProjectToolchainRequirements() { }

    public static List<ToolchainRequirement> from(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime) {
        List<ToolchainRequirement> result = new ArrayList<>();
        switch (runtime) {
            case DeploymentRuntimeSpecification.SpringBoot v -> add(result, facts, JAVA, v.javaVersion());
            case DeploymentRuntimeSpecification.JavaSource v -> add(result, facts, JAVA, v.javaVersion());
            case DeploymentRuntimeSpecification.JavaJar v -> add(result, facts, JAVA, v.javaVersion());
            case DeploymentRuntimeSpecification.NodeService v -> add(result, facts, NODE, "" + v.nodeMajorVersion());
            case DeploymentRuntimeSpecification.PythonService v -> add(result, facts, PYTHON, v.pythonVersion());
            case DeploymentRuntimeSpecification.StaticSite v -> {
                if (v.nodeMajorVersion().isPresent()) add(result, facts, NODE, "" + v.nodeMajorVersion().getAsInt());
                // The managed static server is part of the Python runtime, independently of frontend dependencies. / 受管静态服务器属于 Python 运行时，与前端依赖相互独立。
                add(result, facts, PYTHON, "3.12");
            }
            case DeploymentRuntimeSpecification.KotlinService v -> {
                add(result, facts, JAVA, v.jvmTarget()); add(result, facts, KOTLIN, v.version());
            }
            case DeploymentRuntimeSpecification.GoService v -> add(result, facts, GO, v.version());
            case DeploymentRuntimeSpecification.RustService v -> add(result, facts, RUST, v.version());
            case DeploymentRuntimeSpecification.DotNetService v -> add(result, facts, DOTNET, v.version());
            case DeploymentRuntimeSpecification.PhpService v -> add(result, facts, PHP, v.version());
            case DeploymentRuntimeSpecification.RubyService v -> add(result, facts, RUBY, v.version());
            case DeploymentRuntimeSpecification.CmakeService ignored -> result.addAll(facts.toolchainRequirements().stream()
                    .filter(r -> r.ecosystem() == C || r.ecosystem() == CPP).toList());
            case DeploymentRuntimeSpecification.Container ignored -> { }
        }
        return List.copyOf(result);
    }

    private static void add(List<ToolchainRequirement> result, DeploymentProjectFacts facts,
            ToolchainEcosystemType ecosystem, String reviewed) {
        ToolchainRequirement selected = ToolchainRequirement.declared(ecosystem, reviewed, "reviewed-runtime", BUILD);
        // Preserve an exact source patch when a form only represents its branch. Explicit different review inputs win. / 当表单仅表示版本分支时保留源码的精确补丁版本，显式不同的审阅输入优先。
        var exact = facts.toolchainRequirements().stream().filter(r -> r.ecosystem() == ecosystem
                && r.constraint() == ToolchainRequirement.ConstraintType.EXACT && r.version().isPresent()
                && selected.version().isPresent() && selected.constraint() == ToolchainRequirement.ConstraintType.SERIES
                && r.version().orElseThrow().branch().equals(selected.version().orElseThrow().branch())).toList();
        if (exact.stream().map(r -> r.version().orElseThrow().text()).distinct().count() > 1)
            throw new IllegalArgumentException("conflicting exact toolchain declarations: " + ecosystem);
        if (!exact.isEmpty()) { result.add(exact.getFirst()); return; }
        var original = facts.toolchainRequirements().stream().filter(r -> r.ecosystem() == ecosystem
                && r.version().isPresent() && selected.version().isPresent()
                && (r.version().orElseThrow().sameRelease(selected.version().orElseThrow())
                || selected.constraint() == ToolchainRequirement.ConstraintType.SERIES
                && r.version().orElseThrow().branch().equals(selected.version().orElseThrow().branch()))).findFirst();
        result.add(original.orElse(selected));
    }
}
