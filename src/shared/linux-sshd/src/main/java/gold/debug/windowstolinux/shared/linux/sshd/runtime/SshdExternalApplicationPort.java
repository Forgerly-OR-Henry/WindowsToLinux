package gold.debug.windowstolinux.shared.linux.sshd.runtime;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.runtime.ExternalApplicationPort;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.model.lifecycle.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Runs a fixed discovery/lifecycle program without installing or rewriting services. / 执行固定发现和生命周期程序，不安装或重写服务。 */
public final class SshdExternalApplicationPort implements ExternalApplicationPort {
    private final SshCommandExecutor commands;

    /** Binds to the existing authenticated command channel. / 绑定既有已认证命令通道。 */
    public SshdExternalApplicationPort(SshCommandExecutor commands) { this.commands = java.util.Objects.requireNonNull(commands); }

    @Override public ExternalApplicationScan scan() throws LinuxOperationException { return invoke(List.of("SCAN")); }

    @Override public DiscoveredApplication execute(ExternalApplicationTarget target, LifecycleAction action) throws LinuxOperationException {
        if (action == LifecycleAction.ENABLE_AUTOSTART || action == LifecycleAction.DISABLE_AUTOSTART)
            throw new IllegalArgumentException("external configuration changes are not supported");
        var result = invoke(List.of(target.kind().name(), target.identity(), target.fingerprint(), action.name()));
        if (result.applications().size() != 1 || !result.applications().getFirst().target().equals(target))
            throw LinuxOperationException.create(LinuxOperationFailureType.EXTERNAL_IDENTITY_CHANGED, "External target differs from the scanned identity");
        return result.applications().getFirst();
    }

    private ExternalApplicationScan invoke(List<String> arguments) throws LinuxOperationException {
        try (var resource = getClass().getResourceAsStream("external-applications.py")) {
            if (resource == null) throw new java.io.IOException("missing external application program");
            String command = "exec python3 - " + arguments.stream().map(SshCommandExecutor::quote).collect(java.util.stream.Collectors.joining(" "));
            var result = commands.execProtocolWithInput(command, resource.readAllBytes(), Duration.ofSeconds(110), 1048576);
            if (result.output().contains("ERROR\tIDENTITY_CHANGED"))
                throw LinuxOperationException.create(LinuxOperationFailureType.EXTERNAL_IDENTITY_CHANGED, "External unit or container changed; scan again");
            if (result.output().contains("ERROR\tMANAGED_OWNERSHIP_REQUIRED"))
                throw LinuxOperationException.create(LinuxOperationFailureType.EXTERNAL_OWNERSHIP_REQUIRED, "Managed ownership validation remains required");
            if (!result.succeeded()) throw LinuxOperationException.create(LinuxOperationFailureType.EXTERNAL_OPERATION_FAILED,
                    "External application operation failed or requires additional runtime permissions");
            return parse(result.output());
        } catch (java.io.IOException | IllegalArgumentException failure) {
            throw LinuxOperationException.create(LinuxOperationFailureType.EXTERNAL_OPERATION_FAILED, "External application protocol is unavailable or invalid", failure);
        }
    }

    static ExternalApplicationScan parse(String output) {
        List<DiscoveredApplication> applications = new ArrayList<>(); List<ExternalScanIssueType> issues = new ArrayList<>();
        boolean ended = false;
        for (String line : output.lines().toList()) {
            if (ended) throw new IllegalArgumentException("trailing scan output");
            String[] fields = line.split("\t", -1);
            if (fields.length == 9 && fields[0].equals("APP")) {
                applications.add(new DiscoveredApplication(new ExternalApplicationTarget(ExternalApplicationKind.valueOf(fields[1]), decode(fields[2]), fields[3]),
                        decode(fields[4]), RuntimeState.valueOf(fields[5]), bit(fields[6]), bit(fields[7]), bit(fields[8])));
            } else if (fields.length == 2 && fields[0].equals("ISSUE")) issues.add(ExternalScanIssueType.valueOf(fields[1]));
            else if (fields.length == 2 && fields[0].equals("END") && Integer.parseInt(fields[1]) == applications.size()) ended = true;
            else throw new IllegalArgumentException("invalid scan output");
        }
        if (!ended) throw new IllegalArgumentException("incomplete scan output");
        return new ExternalApplicationScan(applications, issues);
    }

    private static String decode(String text) {
        if (text.length() > 2048) throw new IllegalArgumentException("scan field too long");
        return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
    }
    private static boolean bit(String value) {
        if (!value.equals("0") && !value.equals("1")) throw new IllegalArgumentException("invalid protocol flag");
        return value.equals("1");
    }
}
