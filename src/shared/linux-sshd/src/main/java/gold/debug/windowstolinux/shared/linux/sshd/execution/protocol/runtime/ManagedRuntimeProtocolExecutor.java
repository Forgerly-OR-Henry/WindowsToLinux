package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;

/**
 * Controls only managed runtime observation, lifecycle, and bounded retention through fixed helper verbs. / 仅通过固定 helper 动词控制受管运行时观察、生命周期与有界保留。
 */
public final class ManagedRuntimeProtocolExecutor {
    /**
     * Bound ssh command executor collaborator for typed remote command boundary.
     * <p>处理类型化远端命令边界的SSH命令执行器协作对象。
     */
    private final SshCommandExecutor commands;

    /**
     * Creates a managed runtime controller. / 创建受管运行时控制器。
     *
     * @param commands typed remote command boundary / 类型化远端命令边界
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ManagedRuntimeProtocolExecutor(SshCommandExecutor commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    /**
     * Returns the helper-verified current runtime kind. / 返回 helper 验证的当前运行时类型。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @return the helper-verified current runtime kind /  helper 验证的当前运行时类型
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public Map<String, String> inspect(ManagedApplication application) throws LinuxOperationException {
        var result = commands.execProtocol(command("inspect-runtime", application), Duration.ofSeconds(20), true);
        if (!result.succeeded()) {
            throw LinuxOperationException.create(LinuxOperationFailureType.RUNTIME_OBSERVATION_FAILED,
                    "Controlled helper could not identify the managed runtime: " + result.failureEvidence());
        }
        return gold.debug.windowstolinux.shared.linux.command.CommandText.lines(result.output());
    }

    /**
     * Executes one allowlisted ordinary-runtime lifecycle action. / 执行一个列入白名单的普通运行时生命周期动作。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @return constructed or resolved remote step result / 构造或解析得到的远端步骤结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public RemoteStepResult lifecycle(ManagedApplication application, String action) throws LinuxOperationException {
        if (!java.util.Set.of("start", "stop", "restart", "enable", "disable").contains(action)) {
            throw new IllegalArgumentException("unsupported managed lifecycle action");
        }
        String command = gold.debug.windowstolinux.shared.linux.command.CommandText.quote(ManagedHelperBundle.PATH)
                + " 'lifecycle' " + gold.debug.windowstolinux.shared.linux.command.CommandText.quote(application.id())
                + ' ' + gold.debug.windowstolinux.shared.linux.command.CommandText.quote(action) + ' '
                + gold.debug.windowstolinux.shared.linux.command.CommandText
                        .quote(application.ownershipManifestSha256());
        var result = commands.exec(command, Duration.ofSeconds(60), true);
        return new RemoteStepResult(result.succeeded(), result.timedOut(),
                (result.succeeded()
                        ? "Controlled helper executed the ordinary managed lifecycle action"
                        : result.failureEvidence()) + stopEvidence(result.output()));
    }

    /**
     * Decodes complete native service evidence without treating missing data as stopped. / 解码完整原生服务证据，缺失信息不视为停止。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @return complete native service evidence without treating missing data as stopped / 完整原生服务证据，缺失信息不视为停止
     */
    public static LifecycleObservation nativeObservation(ManagedApplication application, Map<String, String> values) {
        boolean owned = "1".equals(values.get("OWNER"));
        RuntimeState state = owned ? nativeState(values) : RuntimeState.UNKNOWN;
        String enabled = values.getOrDefault("ENABLED", "unknown");
        AutostartState autostart = !owned ? AutostartState.UNKNOWN : switch (enabled) {
            case "enabled" -> AutostartState.ENABLED;
            case "disabled" -> AutostartState.DISABLED;
            default -> AutostartState.UNKNOWN;
        };
        String evidence = !owned
                ? "Managed runtime ownership could not be verified"
                : (!values.containsKey("QUERY_OK") || ("1".equals(values.get("QUERY_OK")) && !values.keySet()
                        .containsAll(List.of("ActiveState", "SubState", "Result", "ExecMainCode", "ExecMainStatus",
                                "MainPID"))))
                                        ? "Incomplete helper observation; run environment preparation first"
                                        : "Native service observation" + nativeEvidence(values, "");
        return new LifecycleObservation(application, state, autostart, owned, Instant.now(), evidence);
    }

    /**
     * Derives native runtime state only from complete valid systemd query evidence, otherwise preserving UNKNOWN.
     * <p>仅根据完整有效的 systemd 查询证据推导原生运行状态，否则保留 UNKNOWN。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @return constructed or resolved runtime state / 构造或解析得到的运行时状态
     */
    private static RuntimeState nativeState(Map<String, String> values) {
        if (!"1".equals(values.get("QUERY_OK")))
            return RuntimeState.UNKNOWN;
        for (String key : List.of("ActiveState", "SubState", "Result", "ExecMainCode", "ExecMainStatus", "MainPID")) {
            if (!values.getOrDefault(key, "").matches("[A-Za-z0-9_-]{1,64}"))
                return RuntimeState.UNKNOWN;
        }
        for (String key : List.of("ExecMainCode", "ExecMainStatus", "MainPID")) {
            if (!values.get(key).matches("[0-9]{1,10}"))
                return RuntimeState.UNKNOWN;
        }
        String active = values.get("ActiveState"), sub = values.get("SubState"), result = values.get("Result");
        if (active.equals("failed")
                || (active.equals("activating") && sub.equals("auto-restart") && !result.equals("success")))
            return RuntimeState.ERROR;
        if (active.equals("active") && sub.equals("running") && result.equals("success")
                && !values.get("MainPID").equals("0"))
            return RuntimeState.RUNNING;
        if (active.equals("inactive") && sub.equals("dead") && values.get("MainPID").equals("0"))
            return RuntimeState.STOPPED;
        return RuntimeState.UNKNOWN;
    }

    /**
     * Formats the allowlisted systemd query fields with a supplied key prefix as runtime evidence.
     * <p>将具有指定键前缀的白名单 systemd 查询字段格式化为运行证据。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param prefix prefix / 前缀
     * @return the allowlisted systemd query fields with a supplied key prefix as runtime evidence / 将具有指定键前缀的白名单 systemd 查询字段格式化为运行证据
     */
    private static String nativeEvidence(Map<String, String> values, String prefix) {
        StringBuilder evidence = new StringBuilder();
        for (String key : List.of("QUERY_OK", "ActiveState", "SubState", "Result", "ExecMainCode", "ExecMainStatus",
                "MainPID")) {
            String value = values.getOrDefault(prefix + key, "missing");
            evidence.append("; ").append(prefix).append(key).append('=')
                    .append(value.matches("[A-Za-z0-9_-]{1,64}") ? value : "invalid");
        }
        return evidence.toString();
    }

    /**
     * Preserves allowlisted stop diagnostics after the helper clears a verified failure. / helper 清理已验证失败后保留白名单停机诊断。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @return stop evidence text / 停止证据文本
     */
    public static String stopEvidence(String output) {
        Map<String, String> values = gold.debug.windowstolinux.shared.linux.command.CommandText.lines(output);
        return values.containsKey("STOP_BEFORE_QUERY_OK")
                ? nativeEvidence(values, "STOP_BEFORE_") + nativeEvidence(values, "STOP_AFTER_")
                : "";
    }

    /**
     * Retains current plus at most two previous verified releases. / 保留当前发布及最多两个已验证的先前发布。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @return constructed or resolved remote step result / 构造或解析得到的远端步骤结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public RemoteStepResult retain(ManagedApplication application) throws LinuxOperationException {
        var result = commands.exec(command("retain", application), Duration.ofSeconds(60), true);
        return new RemoteStepResult(result.succeeded(), result.timedOut(),
                result.succeeded()
                        ? "Controlled helper retained the bounded recent verified releases"
                        : "Controlled helper could not finish bounded release retention: " + result.failureEvidence());
    }

    /**
     * Renders a fixed helper invocation with individually quoted reviewed arguments; does not execute it.
     * <p>使用逐项引用的已审阅参数渲染固定 helper 调用，不执行该调用。
     *
     * @param verb verb / 操作动词
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @return command text / 命令文本
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String command(String verb, ManagedApplication application) {
        Objects.requireNonNull(application, "application");
        return gold.debug.windowstolinux.shared.linux.command.CommandText.quote(ManagedHelperBundle.PATH) + ' '
                + gold.debug.windowstolinux.shared.linux.command.CommandText.quote(verb) + ' '
                + gold.debug.windowstolinux.shared.linux.command.CommandText.quote(application.id()) + ' '
                + gold.debug.windowstolinux.shared.linux.command.CommandText
                        .quote(application.ownershipManifestSha256());
    }
}
