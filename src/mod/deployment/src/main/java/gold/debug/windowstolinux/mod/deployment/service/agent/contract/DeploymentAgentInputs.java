package gold.debug.windowstolinux.mod.deployment.service.agent.contract;

import java.security.MessageDigest;
import java.util.*;

import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentAuthorizationPolicy;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentResourceBindings;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;

/** Immutable agent input and original target identity helpers. / 不可变Agent输入及原目标身份工具。 */
public final class DeploymentAgentInputs {
    /** Binds only the original inputs and capabilities owned by this adapter. / 仅绑定当前适配所属的原输入及能力。 */
    private DeploymentAgentInputs() {
    }

    /** Unchanged original autonomous deployment task type. / 未变的原自主部署任务类型。 */
    public static final String TASK_TYPE = "deployment-agent";

    /** Captured original autonomous source, target and policy input. / 已捕获原自主来源、目标及策略输入。
     * @param requirements original requirements bound to this operation / 当前操作绑定的原requirements值
     * @param approvalMode captured approval policy / 已捕获审批策略
     * @param rememberAuthorization captured exact-action reuse preference / 已捕获精确动作复用偏好
     * @param sourceBytes maximum frozen source bytes / 冻结来源字节上限
     * @param sourceEntries maximum discoverable source entries / 可发现来源条目上限
     * @param archiveBytes maximum compressed source archive bytes / 压缩来源归档字节上限 */
    public record Request(Map<String, String> requirements, String approvalMode, boolean rememberAuthorization,
                          long sourceBytes, int sourceEntries,
                          long archiveBytes) {
        /** Freezes the exact user choice. / 冻结精确用户选择。
         * @param requirements bounded constraints / 有界约束
         * @param approvalMode chosen policy / 所选策略
         * @param rememberAuthorization chosen rememberAuthorization repetition / 所选授权重复规则
         * @param sourceBytes source budget / 来源额度
         * @param sourceEntries discovery budget / 发现额度
         * @param archiveBytes archive budget / 归档额度 */
        public Request {
            requirements = Map.copyOf(requirements);
            DeploymentAuthorizationPolicy.requireAllowed(approvalMode, rememberAuthorization);
            if (requirements.size() > 64 || requirements.toString().length() > 8000
                || !Set.of("MANUAL_REVIEW", "AUTOMATIC", "FULL_CONTROL").contains(approvalMode) || sourceBytes < 1
                || sourceBytes > 2L * 1024 * 1024 * 1024 || sourceEntries < 1 || sourceEntries > 100_000
                || archiveBytes < 1 || archiveBytes > 2L * 1024 * 1024 * 1024)
                throw new IllegalArgumentException("Invalid agent task request");
        }
    }

    /** Reads the exact original target profile without minting server identity. / 读取精确原目标档案，不另造服务器身份。
     * @param server exact original target server / 精确原目标服务器
     * @return exact original server profile identity / 精确原服务器档案身份 */
    public static String targetProfile(gold.debug.windowstolinux.appapi.server.model.ServerInfo server) {
        return hash(server.ref().id() + "\n" + server.host() + "\n" + server.port() + "\n" + server.username());
    }

    /** Binds all captured target facts to one original task digest. / 将全部已捕获目标事实绑定为一个原任务摘要。
     * @param server exact original target server / 精确原目标服务器
     * @param session original session bound to this operation / 当前操作绑定的原session值
     * @return digest of captured original target facts / 已捕获原目标事实摘要 */
    public static String targetDigest(ServerId server, RemoteSession session) {
        return hash(server.id() + ":" + session.facts().fingerprint());
    }

    /** Computes the stable digest of the original target description. / 计算原目标说明的稳定摘要。
     * @param value caller-owned value validated by this contract / 当前合同校验的调用方所属值
     * @return canonical lowercase target digest / 规范小写目标摘要 */
    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    /** Preserves the original Agent release derivation; the artifact and release identify different objects. */
    public static String publicationReleaseIdentity(String artifact, String sourceRevision, String configuration,
                                                     DeploymentRuntimeDefinition runtime, DeploymentResourceBindings resources) {
        return hash("agent-release:" + artifact + ":" + sourceRevision + ":" + configuration + ":" + runtime + ":" + resources);
    }

    /** Builds a stable physical target description from the original host facts. / 从原主机事实建立稳定物理目标说明。
     * @param server exact original target server / 精确原目标服务器
     * @return stable original physical target description / 稳定原物理目标说明 */
    public static String physicalTarget(gold.debug.windowstolinux.appapi.server.model.ServerInfo server) {
        return hash(server.host().toLowerCase(java.util.Locale.ROOT) + "\n" + server.port());
    }
}
