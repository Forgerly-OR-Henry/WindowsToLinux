package gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem;

import gold.debug.windowstolinux.shared.linux.sshd.build.generation.script.SafeBuildScriptEnvelope;
import gold.debug.windowstolinux.shared.linux.sshd.build.contract.spi.DeploymentBuildRenderer;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.Set;

/** Renders the fixed single-target CMake experimental architecture. / 渲染固定单目标 CMake 试验架构。 */
public final class CmakeBuildRenderer implements DeploymentBuildRenderer {
    /** Returns the CMake service type. / 返回 CMake 服务类型。 */
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.CMAKE_SERVICE; }
    /** Returns the CMake build identity. / 返回 CMake 构建身份。 */
    @Override public Set<DeploymentBuildToolType> buildTools() { return Set.of(DeploymentBuildToolType.CMAKE); }

    /** Renders fixed preset configure/build and ELF dependency verification. / 渲染固定 preset 配置/构建与 ELF 依赖验证。 */
    @Override
    public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                         RemoteWorkspace workspace, BuildLimitConfiguration limits) {
        if (!(runtime instanceof DeploymentRuntimeSpecification.CmakeService cmake)
                || facts.buildTool() != DeploymentBuildToolType.CMAKE) {
            throw new IllegalArgumentException("CMake renderer requires reviewed single-target inputs");
        }
        if (facts.languageFacts().sourceLanguages().isEmpty()) {
            throw new IllegalArgumentException("CMake renderer requires a reviewed C/C++ source language set");
        }
        String preset = SafeBuildScriptEnvelope.shellQuote(cmake.preset());
        String target = SafeBuildScriptEnvelope.shellQuote(cmake.target());
        String compilers = (facts.languageFacts().sourceLanguages().contains(SourceLanguageType.C)
                ? "command -v cc >/dev/null\n" : "")
                + (facts.languageFacts().sourceLanguages().contains(SourceLanguageType.CPP)
                ? "command -v c++ >/dev/null\n" : "");
        String command = """
                command -v cmake >/dev/null
                command -v ninja >/dev/null
                %scommand -v ldd >/dev/null
                preset=%s
                target=%s
                test "$preset" = w2l-release
                test -f ./CMakeLists.txt
                test -f ./CMakePresets.json
                run cmake --preset "$preset"
                run cmake --build --preset "$preset-build" --target "$target" --parallel 1
                artifact="./.w2l/cmake-build/$target"
                test -x "$artifact"
                test ! -L "$artifact"
                mkdir -p ./.w2l/bin
                cp -- "$artifact" "./.w2l/bin/$target"
                chmod 0555 -- "./.w2l/bin/$target"
                ldd "./.w2l/bin/$target" > "./.w2l/bin/$target.ldd"
                ! grep -F 'not found' "./.w2l/bin/$target.ldd"
                printf 'ARTIFACT=%%s\n' "./.w2l/bin/$target"
                """.formatted(compilers, preset, target);
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits, command);
    }
}
