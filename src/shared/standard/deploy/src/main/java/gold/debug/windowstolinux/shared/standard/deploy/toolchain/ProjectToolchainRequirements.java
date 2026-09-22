package gold.debug.windowstolinux.shared.standard.deploy.toolchain;

import static gold.debug.windowstolinux.shared.model.toolchain.ToolchainEcosystemType.*;
import static gold.debug.windowstolinux.shared.model.toolchain.ToolchainRequirement.PurposeType.*;

import java.util.ArrayList;
import java.util.List;

import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.toolchain.*;

/**
 * Shared projection of reviewed runtime inputs and original declarations. / 审阅输入与原始声明的公共工具链需求投影。
 */
public final class ProjectToolchainRequirements {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private ProjectToolchainRequirements() {
    }

    /**
     * Derives exact ecosystem tool requirements from the reviewed project architecture and runtime specification.
     * <p>根据已审阅项目架构及运行规格派生精确生态工具要求。
     *
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @return constructed or resolved list / 构造或解析得到的列表
     */
    public static List<ToolchainRequirement> from(DeploymentProjectFacts facts,
            DeploymentRuntimeSpecification runtime) {
        List<ToolchainRequirement> result = new ArrayList<>();
        switch (runtime) {
            case DeploymentRuntimeSpecification.SpringBoot v -> add(result, facts, JAVA, v.javaVersion());
            case DeploymentRuntimeSpecification.JavaSource v -> add(result, facts, JAVA, v.javaVersion());
            case DeploymentRuntimeSpecification.JavaJar v -> add(result, facts, JAVA, v.javaVersion());
            case DeploymentRuntimeSpecification.NodeService v -> add(result, facts, NODE, "" + v.nodeMajorVersion());
            case DeploymentRuntimeSpecification.PythonService v -> add(result, facts, PYTHON, v.pythonVersion());
            case DeploymentRuntimeSpecification.StaticSite v -> {
                if (v.nodeMajorVersion().isPresent())
                    add(result, facts, NODE, "" + v.nodeMajorVersion().getAsInt());
                // The managed static server is part of the Python runtime, independently of frontend dependencies. / 受管静态服务器属于 Python 运行时，与前端依赖相互独立。
                add(result, facts, PYTHON, "3.12");
            }
            case DeploymentRuntimeSpecification.KotlinService v -> {
                add(result, facts, JAVA, v.jvmTarget());
                add(result, facts, KOTLIN, v.version());
            }
            case DeploymentRuntimeSpecification.GoService v -> add(result, facts, GO, v.version());
            case DeploymentRuntimeSpecification.RustService v -> add(result, facts, RUST, v.version());
            case DeploymentRuntimeSpecification.DotNetService v -> add(result, facts, DOTNET, v.version());
            case DeploymentRuntimeSpecification.PhpService v -> add(result, facts, PHP, v.version());
            case DeploymentRuntimeSpecification.RubyService v -> add(result, facts, RUBY, v.version());
            case DeploymentRuntimeSpecification.CmakeService ignored -> result.addAll(facts.toolchainRequirements()
                    .stream().filter(r -> r.ecosystem() == C || r.ecosystem() == CPP).toList());
            case DeploymentRuntimeSpecification.Container ignored -> {
            }
            case DeploymentRuntimeSpecification.ManagedProcess ignored ->
                throw new IllegalArgumentException("generic runtime has no standard toolchain policy");
        }
        if (!runtime.workload().companions().isEmpty())
            result.addAll(facts.toolchainRequirements().stream().filter(r -> r.ecosystem() == C || r.ecosystem() == CPP)
                    .filter(r -> !result.contains(r)).toList());
        return List.copyOf(result);
    }

    /**
     * Adds project toolchain requirements.
     * <p>添加项目工具链要求集合。
     *
     * @param result typed outcome produced by the delegated operation / 被委派操作产生的类型化结果
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param ecosystem ecosystem / 生态
     * @param reviewed reviewed / 已审阅
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static void add(List<ToolchainRequirement> result, DeploymentProjectFacts facts,
            ToolchainEcosystemType ecosystem, String reviewed) {
        ToolchainRequirement selected = ToolchainRequirement.declared(ecosystem, reviewed, "reviewed-runtime", BUILD);
        // Preserve an exact source patch when a form only represents its branch. Explicit different review inputs win. / 当表单仅表示版本分支时保留源码的精确补丁版本，显式不同的审阅输入优先。
        var exact = facts.toolchainRequirements().stream()
                .filter(r -> r.ecosystem() == ecosystem && r.constraint() == ToolchainRequirement.ConstraintType.EXACT
                        && r.version().isPresent() && selected.version().isPresent()
                        && selected.constraint() == ToolchainRequirement.ConstraintType.SERIES
                        && r.version().orElseThrow().branch().equals(selected.version().orElseThrow().branch()))
                .toList();
        if (exact.stream().map(r -> r.version().orElseThrow().text()).distinct().count() > 1)
            throw new IllegalArgumentException("conflicting exact toolchain declarations: " + ecosystem);
        if (!exact.isEmpty()) {
            result.add(exact.getFirst());
            return;
        }
        var original = facts.toolchainRequirements().stream()
                .filter(r -> r.ecosystem() == ecosystem && r.version().isPresent() && selected.version().isPresent()
                        && (r.version().orElseThrow().sameRelease(selected.version().orElseThrow())
                                || selected.constraint() == ToolchainRequirement.ConstraintType.SERIES && r.version()
                                        .orElseThrow().branch().equals(selected.version().orElseThrow().branch())))
                .findFirst();
        result.add(original.orElse(selected));
    }
}
