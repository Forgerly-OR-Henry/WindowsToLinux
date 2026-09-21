package gold.debug.windowstolinux.shared.linux.sshd.distro.dnf;

import gold.debug.windowstolinux.shared.linux.distro.SelinuxEnvironmentPreparer;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityState;
import gold.debug.windowstolinux.shared.model.server.security.SelinuxPreparationPlan;
import gold.debug.windowstolinux.shared.model.server.security.SelinuxPreparationState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Executes only fixed, reviewed SELinux preparation steps. / 仅执行固定且经审阅的 SELinux 准备步骤。
 */
public final class SelinuxPreparationExecutor implements SelinuxEnvironmentPreparer {
    /**
     * Bound ssh command executor collaborator for typed remote command boundary.
     * <p>处理类型化远端命令边界的SSH命令执行器协作对象。
     */
    private final SshCommandExecutor commands;
    /**
     * Persisted server identifier.
     * <p>持久化服务器标识。
     */
    private final String serverId;

    /**
     * Validates and binds the inputs required by selinux preparation executor.
     * <p>校验并绑定Selinux准备执行器所需输入。
     *
     * @param commands typed remote command boundary / 类型化远端命令边界
     * @param serverId persisted server identifier / 持久化服务器标识
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public SelinuxPreparationExecutor(SshCommandExecutor commands, String serverId) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
    }

    /**
     * Inspects optional.
     * <p>检查可选。
     *
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    @Override public java.util.Optional<SelinuxPreparationPlan> inspect() throws LinuxOperationException {
        Map<String, String> values = SshCommandExecutor.lines(execute("inspect", null));
        if ("false".equals(values.get("APPLICABLE"))) return java.util.Optional.empty();
        try {
            if (!"true".equals(values.get("APPLICABLE"))) throw new IllegalArgumentException("Missing applicability");
            return java.util.Optional.of(new SelinuxPreparationPlan(serverId, values.get("BOOT_ID"), values.get("CONFIG_SHA256"),
                    LinuxSecurityState.valueOf(values.get("SECURITY_STATE")),
                    SelinuxPreparationState.valueOf(values.get("PREPARATION_STATE"))));
        } catch (RuntimeException failure) {
            throw LinuxOperationException.create(LinuxOperationFailureType.ENVIRONMENT_PREPARATION_FAILED,
                    "Invalid SELinux preparation observation", failure);
        }
    }

    /**
     * Prepares reboot.
     * <p>准备重启。
     *
     * @param approved approved / 已批准
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override public void prepareReboot(SelinuxPreparationPlan approved) throws LinuxOperationException {
        execute("prepare", approved);
    }

    /**
     * Enables enforcement.
     * <p>启用强制执行。
     *
     * @param approved approved / 已批准
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override public void enableEnforcement(SelinuxPreparationPlan approved) throws LinuxOperationException {
        execute("enforce", approved);
    }

    /**
     * Commits enforcement.
     * <p>提交强制执行。
     *
     * @param approved approved / 已批准
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override public void commitEnforcement(SelinuxPreparationPlan approved) throws LinuxOperationException {
        execute("commit", approved);
    }

    /**
     * Executes selinux preparation.
     * <p>执行Selinux准备。
     *
     * @param operation operation / 操作
     * @param approved approved / 已批准
     * @return execute text / 执行文本
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private String execute(String operation, SelinuxPreparationPlan approved) throws LinuxOperationException {
        if (approved != null && !serverId.equals(approved.serverId())) {
            throw LinuxOperationException.create(LinuxOperationFailureType.ENVIRONMENT_PREPARATION_FAILED,
                    "System preparation approval targets another server");
        }
        String arguments = "'" + operation + "'";
        if (approved != null) {
            arguments += " '" + approved.bootId() + "' '" + approved.configurationSha256()
                    + "' '" + approved.state().name() + "' '" + approved.securityState().name() + "'";
        }
        String script;
        try {
            script = loadScript();
        } catch (IOException failure) {
            throw LinuxOperationException.create(LinuxOperationFailureType.ENVIRONMENT_PREPARATION_FAILED,
                    "Cannot load SELinux preparation resource", failure);
        }
        var result = commands.execScript(script + "\nselinux_preparation " + arguments + "\n",
                operation.equals("inspect") ? Duration.ofSeconds(30) : Duration.ofMinutes(3), true);
        if (!result.succeeded()) {
            throw LinuxOperationException.create(LinuxOperationFailureType.ENVIRONMENT_PREPARATION_FAILED,
                    "SELinux preparation " + operation + " failed: " + result.failureEvidence());
        }
        return result.output();
    }

    /**
     * Loads build script path.
     * <p>加载构建脚本路径。
     *
     * @return build script path / 构建脚本路径
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    static String loadScript() throws IOException {
        try (var input = SelinuxPreparationExecutor.class.getResourceAsStream("selinux-preparation.sh")) {
            if (input == null) throw new IOException("Missing SELinux preparation resource");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
