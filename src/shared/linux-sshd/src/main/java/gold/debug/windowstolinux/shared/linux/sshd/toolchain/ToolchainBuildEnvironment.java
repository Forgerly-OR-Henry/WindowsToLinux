package gold.debug.windowstolinux.shared.linux.sshd.toolchain;

import gold.debug.windowstolinux.shared.model.toolchain.*;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import java.util.ArrayList;
import java.util.List;

/**
 * Explicit executable paths and selected versions; project language targets remain untouched. / 明确可执行路径与选择版本，保留项目语言目标。
 */
public final class ToolchainBuildEnvironment {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private ToolchainBuildEnvironment() { }
    /**
     * Renders environment assignments that bind the build to its exact reviewed toolchain executables.
     * <p>渲染将构建绑定到精确已审阅工具链可执行文件的环境赋值。
     *
     * @param tools tools / 工具集合
     * @return render text / 渲染文本
     */
    public static String render(ResolvedToolchainSet tools) {
        if (tools.selections().isEmpty()) return "";
        StringBuilder script = new StringBuilder();
        List<String> paths = new ArrayList<>();
        for (var s : tools.selections()) {
            String name = s.version().ecosystem().name();
            String path = s.directory();
            paths.add(path + (s.version().ecosystem() == ToolchainEcosystemType.DOTNET ? "" : "/bin"));
            variable(script, "WTL_" + name + "_VERSION", s.version().text());
            variable(script, "WTL_" + name + "_BRANCH", s.version().branch());
            switch (s.version().ecosystem()) {
                case JAVA -> {
                    variable(script, "JAVA_HOME", path);
                    variable(script, "JDK_HOME", path);
                    variable(script, "WTL_JAVA_HOME", path);
                }
                case DOTNET -> {
                    variable(script, "DOTNET_ROOT", path);
                    variable(script, "DOTNET_MULTILEVEL_LOOKUP", "0");
                }
                case PYTHON -> variable(script, "WTL_PYTHON", path + "/bin/python3");
                case GO -> { variable(script, "GOROOT", path); variable(script, "GOTOOLCHAIN", "local"); }
                case RUST -> { variable(script, "RUSTC", path + "/bin/rustc"); variable(script, "RUSTDOC", path + "/bin/rustdoc"); }
                default -> { }
            }
        }
        paths.add("/usr/local/bin"); paths.add("/usr/bin"); paths.add("/bin");
        variable(script, "PATH", String.join(":", paths));
        for (var selection : tools.selections()) verifyExecutables(script, selection);
        return script.toString();
    }
    /**
     * Renders checks that required toolchain executable paths and versions match the saved binding before a build.
     * <p>渲染检查，在构建前确认所需工具链可执行路径及版本匹配已保存绑定。
     *
     * @param script build script path / 构建脚本路径
     * @param selection selection / 选择
     */
    private static void verifyExecutables(StringBuilder script, ResolvedToolchainSet.Selection selection) {
        ToolchainEcosystemType ecosystem = selection.version().ecosystem();
        List<String> names = switch (ecosystem) {
            case JAVA -> List.of("java", "javac", "jar");
            case NODE -> List.of("node");
            case PYTHON -> List.of("python3");
            case DOTNET -> List.of("dotnet");
            case KOTLIN -> List.of("kotlinc");
            case GO -> List.of("go");
            case RUST -> List.of("rustc");
            case PHP -> List.of("php");
            case RUBY -> List.of("ruby");
            case C, CPP -> List.of();
        };
        for (String name : names) {
            String executable = selection.directory() + (ecosystem == ToolchainEcosystemType.DOTNET ? "/" : "/bin/") + name;
            String quoted = SshCommandExecutor.quote(executable);
            script.append("if [ \"$(command -v ").append(name).append(")\" != ").append(quoted)
                    .append(" ] || [ ! -x ").append(quoted).append(" ]; then\n")
                    .append("  printf 'TOOLCHAIN_FAILURE=integrity:selected ").append(ecosystem).append(' ').append(name)
                    .append(" is inaccessible to the build identity\\n'\n  exit 65\nfi\n");
        }
    }
    /**
     * Appends an exported shell environment assignment with the value quoted as a single literal.
     * <p>追加导出的 shell 环境赋值，并将值引用为单个字面量。
     *
     * @param out out / 输出
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    private static void variable(StringBuilder out, String name, String value) {
        out.append("export ").append(name).append('=').append(SshCommandExecutor.quote(value)).append('\n');
    }
}
