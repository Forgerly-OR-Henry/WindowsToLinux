package gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.kotlin;

import gold.debug.windowstolinux.shared.linux.sshd.build.generation.script.SafeBuildScriptEnvelope;
import gold.debug.windowstolinux.shared.linux.sshd.build.contract.spi.DeploymentBuildRenderer;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.Set;

/**
 * Renders dependency-free Kotlin/JVM source with the native compiler. / 使用原生编译器渲染无依赖 Kotlin/JVM 源码。
 */
public final class KotlinCompilerBuildRenderer implements DeploymentBuildRenderer {
    /**
     * Returns the Kotlin service type. / 返回 Kotlin 服务类型。
     *
     * @return the Kotlin service type /  Kotlin 服务类型
     */
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.KOTLIN_SERVICE; }
    /**
     * Returns the kotlinc build identity. / 返回 kotlinc 构建身份。
     *
     * @return the kotlinc build identity /  kotlinc 构建身份
     */
    @Override public Set<DeploymentBuildToolType> buildTools() { return Set.of(DeploymentBuildToolType.KOTLINC); }

    /**
     * Renders an exact-version compiler invocation and one runnable JAR. / 渲染精确版本编译器调用与单一可运行 JAR。
     *
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @return render text / 渲染文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    @Override
    public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                         RemoteWorkspace workspace, BuildLimitConfiguration limits) {
        if (!(runtime instanceof DeploymentRuntimeSpecification.KotlinService kotlin)
                || facts.buildTool() != DeploymentBuildToolType.KOTLINC) {
            throw new IllegalArgumentException("Kotlin compiler renderer requires reviewed kotlinc inputs");
        }
        String version = kotlin.version();
        String command = """
                kotlin_compiler="$(command -v kotlinc || true)"
                test -x "$kotlin_compiler"
                command -v java >/dev/null
                java -version 2>&1
                compiler_version="${WTL_KOTLIN_VERSION:-%s}"
                "$kotlin_compiler" -version 2>&1 | grep -F "kotlinc-jvm $compiler_version"
                test -f ./windowstolinux-kotlin.properties
                source_root=$(sed -n 's/^sourceRoot=//p' ./windowstolinux-kotlin.properties)
                test -n "$source_root"
                case "$source_root" in /*|*..*|*//*|'') exit 64 ;; esac
                test -d "$source_root"
                mapfile -d '' -t sources < <(find -P "$source_root" -type f -name '*.kt' -print0 | LC_ALL=C sort -z)
                test "${#sources[@]}" -ge 1
                mkdir -p ./.w2l/kotlin/lib
                run "$kotlin_compiler" -jvm-target %s -include-runtime -d ./.w2l/kotlin/lib/app.jar "${sources[@]}"
                test -f ./.w2l/kotlin/lib/app.jar
                test ! -L ./.w2l/kotlin/lib/app.jar
                printf 'ARTIFACT=%%s\n' ./.w2l/kotlin/lib/app.jar
                """.formatted(version, SafeBuildScriptEnvelope.shellQuote(kotlin.jvmTarget().equals("8") ? "1.8" : kotlin.jvmTarget()));
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits, command);
    }
}
