package gold.debug.windowstolinux.mod.deployment.service.environment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;

import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.session.RemoteTarget;

/** Fixed complete reviewed resources, with no caller-supplied shell or packages. / 固定完整审核资源，不含调用方提供的 Shell 或软件包。 */
final class DeploymentEnvironmentScripts {
    /** Complete unchanged original shell implementation. / 完整未改变原 Shell 实现。 */
    private static final String BODY = resource("environment.sh") + "\n" + resource("selinux.sh");

    /** One frozen digest avoids repeatedly assembling the large unchanged helper installer. / 一项冻结摘要避免反复组装未改变的大型 helper 安装程序。 */
    private static final String BODY_DIGEST = digest(body(gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original()) + gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript.render());

    /** Prevents construction. / 禁止实例化。 */
    private DeploymentEnvironmentScripts() {
    }

    /** Computes the fixed production resource identity. / 计算固定生产资源身份。
     * @return lower-case SHA-256 / 小写 SHA-256 */
    static String body(gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        String parents = String.join(" ", gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentRemoteLayoutAdmission
            .ancestors(layout.paths().stateRoot()));
        return gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayoutTemplate.render(BODY.replace("@STATE_ANCESTORS@", parents), layout);
    }

    static String bodyDigest(gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        return digest(body(layout) + gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript.render(layout));
    }

    static String bodyDigest() {
        return BODY_DIGEST;
    }

    /** Computes the fixed non-secret original endpoint and wire key identity. / 计算固定非秘密原端点及 wire 密钥身份。
     * @param target fixed target / 固定目标
     * @return original target digest / 原目标摘要 */
    static String targetDigest(RemoteTarget target) {
        return digest(target.server().id() + "\n" + target.taskId() + "\n" + target.host() + "\n" + target.port()
            + "\n" + target.username() + "\n" + target.fingerprint().orElse("first-use"));
    }

    /** Renders one fixed stage with exact original observations and receipt identity. / 渲染一项固定阶段，带精确原观测及回执身份。
     * @param state original operation state / 原操作状态
     * @param stage fixed command stage / 固定命令阶段
     * @param timing original task timing / 原任务时序
     * @return complete immutable request / 完整不可变请求 */
    static CommandRequest request(DeploymentEnvironmentState state, String stage,
                                  gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.Environment timing) {
        if (!Set.of("probe", "verify", "packages", "prepare", "enforce", "commit").contains(stage))
            throw new IllegalArgumentException("Invalid fixed environment command");
        String input = assignments(state, stage) + body(state.layout());
        if (!Set.of("probe", "verify").contains(stage))
            input += gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentRemoteLayoutAdmission.initialize(state.layout());
        String script = "request_sha=" + quote(digest(input)) + "\n" + input + "\nenvironment\n";
        return new CommandRequest("/bin/bash -s", script.getBytes(StandardCharsets.UTF_8),
            Duration.ofSeconds(stage.equals("packages") ? timing.installSeconds() : timing.checkSeconds()), 1024 * 1024);
    }

    /** Computes the canonical stage receipt identity before adding its envelope. / 添加封套前计算规范阶段回执身份。
     * @param state original facts / 原事实
     * @param stage fixed stage / 固定阶段
     * @return request digest matched by original remote receipt / 与原远端回执匹配的请求摘要 */
    static String requestDigest(DeploymentEnvironmentState state, String stage) {
        String input = assignments(state, stage) + body(state.layout());
        if (!Set.of("probe", "verify").contains(stage))
            input += gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentRemoteLayoutAdmission.initialize(state.layout());
        return digest(input);
    }

    /** Renders literal original values for fixed resources. / 为固定资源渲染原字面值。
     * @param state original journal / 原日志
     * @param stage fixed stage / 固定阶段
     * @return escaped complete assignments / 已转义完整赋值 */
    private static String assignments(DeploymentEnvironmentState state, String stage) {
        var observed = state.observation();
        return "task=" + quote(state.target().taskId().toString()) + "\noperation=" + quote(state.operation().toString())
            + "\ntarget_sha=" + quote(targetDigest(state.target())) + "\nbody_sha=" + quote(state.bodySha())
            + "\nstage=" + quote(stage) + "\nexpected_profile=" + quote(observed.map(DeploymentEnvironmentObservation::profile).orElse(""))
            + "\nexpected_boot=" + quote(observed.map(value -> value.boot().toString()).orElse(""))
            + "\nexpected_config=" + quote(observed.map(DeploymentEnvironmentObservation::configSha).orElse(""))
            + "\nexpected_security=" + quote(observed.map(DeploymentEnvironmentObservation::security).orElse(""))
            + "\nexpected_selinux=" + quote(observed.map(DeploymentEnvironmentObservation::selinuxPhase).orElse(""))
            + "\nexpected_firewall=" + quote(observed.map(DeploymentEnvironmentObservation::firewall).orElse("")) + "\n";
    }

    /** Quotes a fixed validated value. / 引用固定已校验值。
     * @param value non-secret literal / 非秘密字面值
     * @return safe Bash literal / 安全 Bash 字面值 */
    private static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    /** Computes a UTF-8 identity. / 计算 UTF-8 身份。
     * @param value exact text / 精确文本
     * @return hexadecimal SHA-256 digest / 十六进制SHA-256摘要 */
    static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK SHA-256 unavailable", impossible);
        }
    }

    /** Loads a required production resource. / 加载必需生产资源。
     * @param name fixed resource name / 固定资源名
     * @return exact UTF-8 bytes as text / 精确 UTF-8 字节文本 */
    private static String resource(String name) {
        try (var stream = DeploymentEnvironmentScripts.class.getResourceAsStream(name)) {
            if (stream == null) throw new IllegalStateException("Environment resource missing");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Environment resource unavailable", failure);
        }
    }
}
