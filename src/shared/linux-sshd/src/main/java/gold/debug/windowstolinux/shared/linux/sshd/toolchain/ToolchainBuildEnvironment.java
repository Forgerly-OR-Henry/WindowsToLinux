package gold.debug.windowstolinux.shared.linux.sshd.toolchain;

import gold.debug.windowstolinux.shared.model.toolchain.*;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import java.util.ArrayList;
import java.util.List;

/** Explicit executable paths and selected versions; project language targets remain untouched. / 明确可执行路径与选择版本，保留项目语言目标。 */
public final class ToolchainBuildEnvironment {
    private ToolchainBuildEnvironment() { }
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
    private static void variable(StringBuilder out, String name, String value) {
        out.append("export ").append(name).append('=').append(SshCommandExecutor.quote(value)).append('\n');
    }
}
