package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.input;

import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle;

import gold.debug.windowstolinux.shared.linux.protocol.RemoteRuntimeConfiguration;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteDeploymentInputs;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteSecretPayload;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Streams and seals runtime inputs through the root-owned helper before publication. / 在发布前通过 root 所有的辅助程序流式传输并封存运行时输入。
 */
public final class DeploymentInputProtocolExecutor {
    /**
     * Bound ssh command executor collaborator for typed remote command boundary.
     * <p>处理类型化远端命令边界的SSH命令执行器协作对象。
     */
    private final SshCommandExecutor commands;

    /**
     * Creates the controlled input protocol. / 创建受控输入协议。
     *
     * @param commands typed remote command boundary / 类型化远端命令边界
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DeploymentInputProtocolExecutor(SshCommandExecutor commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    /**
     * Seals one immutable configuration and exact secret-revision set. / 封存一个不可变配置与精确秘密修订集合。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @return constructed or resolved remote deployment inputs / 构造或解析得到的远端部署输入集合
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public RemoteDeploymentInputs stage(ManagedApplication application, RemoteRuntimeConfiguration configuration,
                                         List<RemoteSecretPayload> secrets) throws LinuxOperationException {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(configuration, "configuration");
        secrets = List.copyOf(Objects.requireNonNull(secrets, "secrets"));
        if (!application.id().equals(configuration.applicationId())) {
            throw new IllegalArgumentException("runtime configuration must match the managed application");
        }
        var manifest = new RemoteDeploymentInputs(configuration.sha256(), secrets.stream().map(RemoteSecretPayload::digest).toList());
        stageConfiguration(application.id(), configuration.sha256(), "systemd",
                DeploymentConfigurationRenderer.systemd(configuration));
        stageConfiguration(application.id(), configuration.sha256(), "container",
                DeploymentConfigurationRenderer.container(configuration));
        for (RemoteSecretPayload secret : secrets.stream()
                .sorted(Comparator.comparing((RemoteSecretPayload value) -> value.digest().identifier())
                        .thenComparingLong(value -> value.digest().revision())).toList()) {
            stageSecret(application.id(), secret);
        }
        return manifest;
    }

    /**
     * Stages reviewed configuration snapshot or settings.
     * <p>暂存已审阅配置快照或设置。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param configurationSha256 the immutable configuration digest / 不可变配置摘要
     * @param format format / 格式
     * @param payload payload / 载荷
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private void stageConfiguration(String applicationId, String configurationSha256, String format, byte[] payload)
            throws LinuxOperationException {
        try {
            execute("stage-config", List.of(applicationId, configurationSha256, format, sha256(payload),
                    Integer.toString(payload.length)), payload);
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    /**
     * Stages secret.
     * <p>暂存秘密。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param secret secret / 秘密
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private void stageSecret(String applicationId, RemoteSecretPayload secret) throws LinuxOperationException {
        byte[] payload = secret.copyValue();
        try {
            execute("stage-secret", List.of(applicationId, secret.digest().identifier(),
                    Long.toString(secret.digest().revision()), secret.digest().sha256(),
                    Integer.toString(secret.digest().byteCount())), payload);
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    /**
     * Executes deployment input protocol.
     * <p>执行部署输入协议。
     *
     * @param verb verb / 操作动词
     * @param arguments literal arguments passed to the fixed command or message template / 传给固定命令或消息模板的字面参数
     * @param payload payload / 载荷
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private void execute(String verb, List<String> arguments, byte[] payload) throws LinuxOperationException {
        StringBuilder command = new StringBuilder("sudo -n ")
                .append(SshCommandExecutor.quote(ManagedHelperBundle.PATH)).append(' ')
                .append(SshCommandExecutor.quote(verb));
        arguments.forEach(value -> command.append(' ').append(SshCommandExecutor.quote(value)));
        var result = commands.execProtocolWithInput(command.toString(), payload, Duration.ofSeconds(30));
        if (!result.succeeded()) {
            throw LinuxOperationException.create(LinuxOperationFailureType.DEPLOYMENT_INPUT_STAGING_FAILED,
                    "Controlled helper could not seal reviewed deployment inputs: " + result.failureEvidence());
        }
    }

    /**
     * Computes the SHA-256 content identity used for independent integrity checks.
     * <p>计算独立完整性检查使用的 SHA-256 内容身份。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return computed SHA-256 content digest / 已计算的 SHA-256 内容摘要
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }
}
