package gold.debug.windowstolinux.mod.deployment.service.environment;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Exact bounded original host observation and receipts. / 精确有界的原主机观测及回执。
 * @param profile fixed original distribution profile / 固定原发行版配置
 * @param boot observed boot UUID / 已观测启动 UUID
 * @param configSha observed SELinux configuration digest or none / 已观测 SELinux 配置摘要或 none
 * @param security observed security posture / 已观测安全姿态
 * @param firewall observed firewall posture / 已观测防火墙姿态
 * @param selinuxPhase original operation security phase / 原操作安全阶段
 * @param packagePhase original operation install phase / 原操作安装阶段
 * @param packageRequest original package request digest or none / 原安装请求摘要或 none
 * @param prepareRequest original prepare digest or none / 原准备摘要或 none
 * @param enforceRequest original enforcement digest or none / 原强制态摘要或 none
 * @param commitRequest original commit digest or none / 原提交摘要或 none */
public record DeploymentEnvironmentObservation(String profile, UUID boot, String configSha, String security,
                                               String firewall, String selinuxPhase, String packagePhase,
                                               String packageRequest, String prepareRequest, String enforceRequest,
                                               String commitRequest) {
    /** Requires exact supported profile, security state and original receipt grammar. / 要求精确受支持配置、安全状态及原回执语法。
     * @param profile original profile / 原配置
     * @param boot boot UUID / 启动 UUID
     * @param configSha configuration digest / 配置摘要
     * @param security security posture / 安全姿态
     * @param firewall firewall posture / 防火墙姿态
     * @param selinuxPhase original SELinux phase / 原 SELinux 阶段
     * @param packagePhase original install phase / 原安装阶段
     * @param packageRequest original install digest / 原安装摘要
     * @param prepareRequest original prepare digest / 原准备摘要
     * @param enforceRequest original enforcement digest / 原强制态摘要
     * @param commitRequest original commit digest / 原提交摘要 */
    public DeploymentEnvironmentObservation {
        DeploymentEnvironmentProfiles.packages(profile);
        java.util.Objects.requireNonNull(boot);
        if (!Set.of("Disabled", "Permissive", "Enforcing", "apparmor:Y", "apparmor:N", "none").contains(security)
            || !Set.of("none", "firewalld:active", "firewalld:inactive", "firewalld:failed", "ufw:active", "ufw:inactive",
            "ufw:failed", "nftables:active", "nftables:inactive", "nftables:unknown").contains(firewall)
            || !Set.of("NOT_APPLICABLE", "UNPREPARED", "REBOOT_PENDING", "READY_TO_ENFORCE", "ENFORCEMENT_PENDING", "COMPLETE").contains(selinuxPhase)
            || !Set.of("ABSENT", "STARTED", "SUCCEEDED", "FAILED").contains(packagePhase))
            throw new IllegalArgumentException("Invalid original environment observation");
        for (String value : new String[]{configSha, packageRequest, prepareRequest, enforceRequest, commitRequest})
            if (value == null || !value.equals("none") && !value.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid original environment receipt digest");
    }

    /** Parses a strict original wire response, including all original operation bindings. / 解析严格原 wire 响应，包括全部原操作绑定。
     * @param output bounded untruncated stdout / 有界且未截断的标准输出
     * @param state original journal facts / 原日志事实
     * @return exact host observation / 精确主机观测 */
    static DeploymentEnvironmentObservation parse(String output, DeploymentEnvironmentState state) {
        var fields = new java.util.LinkedHashMap<String, String>();
        int start = output.lastIndexOf("ENVIRONMENT_FACTS_BEGIN\n");
        if (start < 0) throw new IllegalArgumentException("Environment response has no terminal observation");
        output = output.substring(start + "ENVIRONMENT_FACTS_BEGIN\n".length());
        if (output.length() > 8192 || !output.endsWith("\n"))
            throw new IllegalArgumentException("Invalid environment wire size");
        for (String line : output.split("\n")) {
            int separator = line.indexOf('=');
            if (separator < 1 || fields.putIfAbsent(line.substring(0, separator), line.substring(separator + 1)) != null)
                throw new IllegalArgumentException("Invalid environment wire field");
        }
        requireBindings(fields, state);
        return new DeploymentEnvironmentObservation(fields.get("PROFILE"), UUID.fromString(fields.get("BOOT")),
            fields.get("CONFIG_SHA256"), fields.get("SECURITY"), fields.get("FIREWALL"), fields.get("SELINUX_PHASE"),
            fields.get("PACKAGE_PHASE"), fields.get("PACKAGE_REQUEST"), fields.get("PREPARE_REQUEST"),
            fields.get("ENFORCE_REQUEST"), fields.get("COMMIT_REQUEST"));
    }

    /** Verifies exact envelope fields before accepting remote evidence. / 接受远端依据前核对精确封套字段。
     * @param fields original wire fields / 原 wire 字段
     * @param state original task journal / 原任务日志 */
    private static void requireBindings(Map<String, String> fields, DeploymentEnvironmentState state) {
        if (!fields.keySet().equals(Set.of("TASK", "OPERATION", "TARGET_SHA256", "BODY_SHA256", "PROFILE", "BOOT",
            "CONFIG_SHA256", "SECURITY", "FIREWALL", "SELINUX_PHASE", "PACKAGE_PHASE", "PACKAGE_REQUEST",
            "PREPARE_REQUEST", "ENFORCE_REQUEST", "COMMIT_REQUEST"))
            || !state.target().taskId().toString().equals(fields.get("TASK"))
            || !state.operation().toString().equals(fields.get("OPERATION"))
            || !state.bodySha().equals(fields.get("BODY_SHA256"))
            || !DeploymentEnvironmentScripts.targetDigest(state.target()).equals(fields.get("TARGET_SHA256")))
            throw new IllegalArgumentException("Environment response belongs to another original operation");
        if (state.observation().isPresent() && !state.observation().orElseThrow().profile().equals(fields.get("PROFILE")))
            throw new IllegalArgumentException("Original environment profile changed");
    }
}
