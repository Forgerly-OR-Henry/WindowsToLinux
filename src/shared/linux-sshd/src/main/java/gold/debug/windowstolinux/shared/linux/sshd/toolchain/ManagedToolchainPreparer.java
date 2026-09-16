package gold.debug.windowstolinux.shared.linux.sshd.toolchain;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.model.toolchain.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Official release preparation using a product-owned, bounded remote adapter. / 通过产品自有、有界远程适配器准备官方发布。 */
public final class ManagedToolchainPreparer {
    private final SshCommandExecutor commands;
    private final ToolchainSupportCatalog catalog;
    public ManagedToolchainPreparer(SshCommandExecutor commands, ToolchainSupportCatalog catalog) {
        this.commands = commands; this.catalog = catalog;
    }

    public ResolvedToolchainSet prepare(List<ToolchainRequirement> requirements, int attempt, int timeoutSeconds)
            throws LinuxOperationException {
        if (attempt < 0 || attempt > 1) throw new IllegalArgumentException("only two toolchain attempts are allowed");
        List<ResolvedToolchainSet.Selection> result = new ArrayList<>();
        for (var requirement : requirements) {
            var candidates = catalog.candidates(requirement);
            if (candidates.isEmpty()) throw unsupported("No catalog candidate: " + requirement.ecosystem()
                    + " " + requirement.declaration());
            var branch = candidates.get(Math.min(attempt, candidates.size() - 1));
            if (branch.installation() == ToolchainSupportCatalog.InstallationType.SYSTEM_COMPILER) continue;
            String exact = requirement.constraint() == ToolchainRequirement.ConstraintType.EXACT
                    && !requirement.version().orElseThrow().preview()
                    && requirement.version().orElseThrow().branch().equals(branch.version())
                    ? requirement.version().orElseThrow().text() : "-";
            int timeout = Math.max(30, Math.min(7200, timeoutSeconds));
            var response = commands.exec(command(List.of("prepare", requirement.ecosystem().name(), branch.version(), exact,
                    Integer.toString(timeout)), result), Duration.ofSeconds(timeout + 15L), true);
            var fields = SshCommandExecutor.lines(response.output());
            if (!response.succeeded() || !fields.containsKey("TOOLCHAIN")) {
                String reason = fields.getOrDefault("TOOLCHAIN_FAILURE", response.failureEvidence());
                if (reason.startsWith("unavailable:")) throw unsupported(reason);
                throw LinuxOperationException.create(LinuxOperationFailureType.ENVIRONMENT_PREPARATION_FAILED,
                        "Toolchain preparation: " + reason);
            }
            try {
                String[] f = fields.get("TOOLCHAIN").split("\\|", -1);
                if (f.length != 6 || !f[0].equals(requirement.ecosystem().name())) throw new IllegalArgumentException();
                var version = ToolchainVersion.parse(requirement.ecosystem(), f[1]).orElseThrow();
                if (requirement.constraint() == ToolchainRequirement.ConstraintType.MINIMUM
                        && catalog.permits(version) && version.branch().equals(branch.version())
                        && version.compareTo(requirement.version().orElseThrow()) < 0) {
                    if (attempt == 0 && candidates.size() > 1) return prepare(requirements, 1, timeoutSeconds);
                    throw unsupported("No official patch satisfies minimum " + requirement.declaration());
                }
                // Never trust the adapter's returned version without checking this deployment's catalog and candidate. / 不得直接信任适配器返回的版本，必须先检查本次部署的支持目录和候选。
                new ToolchainSelectionPolicy(catalog).resolve(requirement, branch, List.of(version));
                result.add(new ResolvedToolchainSet.Selection(requirement, version, f[2],
                        ResolvedToolchainSet.OriginType.valueOf(f[3]), f[4], f[5]));
            } catch (IllegalArgumentException exception) {
                throw LinuxOperationException.create(LinuxOperationFailureType.ENVIRONMENT_PREPARATION_FAILED,
                        "Prepared toolchain identity does not match the selected catalog candidate", exception);
            }
        }
        var set = new ResolvedToolchainSet(catalog.revision(), result);
        if (!result.isEmpty()) {
            String encoded = Base64.getEncoder().encodeToString(ToolchainBindingCodec.encode(set).getBytes(StandardCharsets.UTF_8));
            var bound = commands.exec(command(List.of("bind", encoded), result), Duration.ofMinutes(3), true);
            if (!bound.succeeded() || !ToolchainBindingCodec.identity(set).equals(SshCommandExecutor.lines(bound.output()).get("TOOLCHAIN_BINDING")))
                throw LinuxOperationException.create(LinuxOperationFailureType.ENVIRONMENT_PREPARATION_FAILED,
                        "Prepared toolchain binding could not be sealed and re-probed: " + bound.failureEvidence());
        }
        return set;
    }

    static String command(List<String> arguments, List<ResolvedToolchainSet.Selection> prepared) {
        String javaHome = prepared.stream().filter(s -> s.version().ecosystem() == ToolchainEcosystemType.JAVA)
                .map(ResolvedToolchainSet.Selection::directory).findFirst().orElse("");
        List<String> values = new ArrayList<>(arguments);
        if (arguments.getFirst().equals("prepare")) values.add(javaHome.isEmpty() ? "-" : javaHome);
        String prefix = "sudo -n " + SshCommandExecutor.quote(
                gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle.PATH)
                + " prepare-toolchains";
        return prefix + " " + values.stream().map(SshCommandExecutor::quote).collect(java.util.stream.Collectors.joining(" "));
    }

    private static LinuxOperationException unsupported(String detail) {
        return LinuxOperationException.create(LinuxOperationFailureType.ENVIRONMENT_REQUIREMENTS_UNMET, detail);
    }
}
