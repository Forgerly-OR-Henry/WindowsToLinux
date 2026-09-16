package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.runtime;

import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import java.time.Instant;
import java.util.List;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Controls only managed runtime observation, lifecycle, and bounded retention through fixed helper verbs. / 仅通过固定 helper 动词控制受管运行时观察、生命周期与有界保留。 */
public final class ManagedRuntimeProtocolExecutor {
    private final SshCommandExecutor commands;

    /** Creates a managed runtime controller. / 创建受管运行时控制器。 */
    public ManagedRuntimeProtocolExecutor(SshCommandExecutor commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    /** Returns the helper-verified current runtime kind. / 返回 helper 验证的当前运行时类型。 */
    public Map<String, String> inspect(ManagedApplication application) throws LinuxOperationException {
        var result = commands.execProtocol(command("inspect-runtime", application), Duration.ofSeconds(20), true);
        if (!result.succeeded()) {
            throw LinuxOperationException.create(LinuxOperationFailureType.RUNTIME_OBSERVATION_FAILED,
                    "Controlled helper could not identify the managed runtime: " + result.failureEvidence());
        }
        return SshCommandExecutor.lines(result.output());
    }

    /** Executes one allowlisted ordinary-runtime lifecycle action. / 执行一个列入白名单的普通运行时生命周期动作。 */
    public RemoteStepResult lifecycle(ManagedApplication application, String action) throws LinuxOperationException {
        if (!java.util.Set.of("start", "stop", "restart", "enable", "disable").contains(action)) {
            throw new IllegalArgumentException("unsupported managed lifecycle action");
        }
        String command = SshCommandExecutor.quote(ManagedHelperBundle.PATH) + " 'lifecycle' "
                + SshCommandExecutor.quote(application.id()) + ' ' + SshCommandExecutor.quote(action) + ' '
                + SshCommandExecutor.quote(application.ownershipManifestSha256());
        var result = commands.exec(command,
                Duration.ofSeconds(60), true);
        return new RemoteStepResult(result.succeeded(), result.timedOut(), (result.succeeded()
                ? "Controlled helper executed the ordinary managed lifecycle action" : result.failureEvidence()) + stopEvidence(result.output()));
    }

    /** Decodes complete native service evidence without treating missing data as stopped. / 解码完整原生服务证据，缺失信息不视为停止。 */
    public static LifecycleObservation nativeObservation(ManagedApplication application, Map<String, String> values) {
        boolean owned = "1".equals(values.get("OWNER"));
        RuntimeState state = owned ? nativeState(values) : RuntimeState.UNKNOWN;
        String enabled = values.getOrDefault("ENABLED", "unknown");
        AutostartState autostart = !owned ? AutostartState.UNKNOWN : switch (enabled) {
            case "enabled" -> AutostartState.ENABLED;
            case "disabled" -> AutostartState.DISABLED;
            default -> AutostartState.UNKNOWN;
        };
        String evidence = !owned ? "Managed runtime ownership could not be verified"
                : (!values.containsKey("QUERY_OK") || ("1".equals(values.get("QUERY_OK"))
                && !values.keySet().containsAll(List.of("ActiveState", "SubState", "Result", "ExecMainCode", "ExecMainStatus", "MainPID"))))
                ? "Incomplete helper observation; run environment preparation first"
                : "Native service observation" + nativeEvidence(values, "");
        return new LifecycleObservation(application, state, autostart, owned, Instant.now(), evidence);
    }

    private static RuntimeState nativeState(Map<String, String> values) {
        if (!"1".equals(values.get("QUERY_OK"))) return RuntimeState.UNKNOWN;
        for (String key : List.of("ActiveState", "SubState", "Result", "ExecMainCode", "ExecMainStatus", "MainPID")) {
            if (!values.getOrDefault(key, "").matches("[A-Za-z0-9_-]{1,64}")) return RuntimeState.UNKNOWN;
        }
        for (String key : List.of("ExecMainCode", "ExecMainStatus", "MainPID")) {
            if (!values.get(key).matches("[0-9]{1,10}")) return RuntimeState.UNKNOWN;
        }
        String active = values.get("ActiveState"), sub = values.get("SubState"), result = values.get("Result");
        if (active.equals("failed") || (active.equals("activating") && sub.equals("auto-restart")
                && !result.equals("success"))) return RuntimeState.ERROR;
        if (active.equals("active") && sub.equals("running") && result.equals("success")
                && !values.get("MainPID").equals("0")) return RuntimeState.RUNNING;
        if (active.equals("inactive") && sub.equals("dead") && values.get("MainPID").equals("0")) return RuntimeState.STOPPED;
        return RuntimeState.UNKNOWN;
    }

    private static String nativeEvidence(Map<String, String> values, String prefix) {
        StringBuilder evidence = new StringBuilder();
        for (String key : List.of("QUERY_OK", "ActiveState", "SubState", "Result", "ExecMainCode", "ExecMainStatus", "MainPID")) {
            String value = values.getOrDefault(prefix + key, "missing");
            evidence.append("; ").append(prefix).append(key).append('=')
                    .append(value.matches("[A-Za-z0-9_-]{1,64}") ? value : "invalid");
        }
        return evidence.toString();
    }

    /** Preserves allowlisted stop diagnostics after the helper clears a verified failure. / helper 清理已验证失败后保留白名单停机诊断。 */
    public static String stopEvidence(String output) {
        Map<String, String> values = SshCommandExecutor.lines(output);
        return values.containsKey("STOP_BEFORE_QUERY_OK")
                ? nativeEvidence(values, "STOP_BEFORE_") + nativeEvidence(values, "STOP_AFTER_") : "";
    }

    /** Retains current plus at most two previous verified releases. / 保留当前发布及最多两个已验证的先前发布。 */
    public RemoteStepResult retain(ManagedApplication application) throws LinuxOperationException {
        var result = commands.exec(command("retain", application), Duration.ofSeconds(60), true);
        return new RemoteStepResult(result.succeeded(), result.timedOut(), result.succeeded()
                ? "Controlled helper retained the bounded recent verified releases"
                : "Controlled helper could not finish bounded release retention: " + result.failureEvidence());
    }

    private static String command(String verb, ManagedApplication application) {
        Objects.requireNonNull(application, "application");
        return SshCommandExecutor.quote(ManagedHelperBundle.PATH) + ' '
                + SshCommandExecutor.quote(verb) + ' ' + SshCommandExecutor.quote(application.id()) + ' '
                + SshCommandExecutor.quote(application.ownershipManifestSha256());
    }
}
