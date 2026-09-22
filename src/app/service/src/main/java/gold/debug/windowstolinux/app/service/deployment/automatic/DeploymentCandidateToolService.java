package gold.debug.windowstolinux.app.service.deployment.automatic;

import java.util.*;

import gold.debug.windowstolinux.app.service.server.*;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.agent.*;

/** Resolves credentials only for exact task-bound candidate operations. / 仅为精确任务绑定候选操作解析凭据。 */
public final class DeploymentCandidateToolService {
    /** Existing credential and host-key boundary. / 既有凭据及主机密钥边界。 */
    private final ServerUseCaseFacade servers;

    /** Narrow remote gateway. / 窄远端网关。 */
    private final DeploymentLinuxGateway gateway;
    /** Binds existing ports. / 绑定既有端口。
     * @param servers server identities / 服务器身份
     * @param gateway remote sessions / 远端会话
     */
    public DeploymentCandidateToolService(ServerUseCaseFacade servers, DeploymentLinuxGateway gateway) {
        this.servers = servers;
        this.gateway = gateway;
    }

    /** Checks ownership before a query or already-approved cleanup; never takes an arbitrary path. / 查询或已批准清理前检查归属，绝不接受任意路径。
     * @param server frozen server / 冻结服务器
     * @param task task identity / 任务身份
     * @param workspace exact candidate / 精确候选项
     * @param cleanup whether the separately approved action is cleanup / 是否为另行批准的清理动作
     * @param master scoped unlock buffer / 限定解锁缓冲区
     * @return actual bounded observation / 实际有界观测
     * @throws Exception when identity, credentials or remote observation fail / 身份、凭据或远端观测失败时
     */
    public AgentObservation candidate(ServerProfile server, String task, RemoteWorkspace workspace, boolean cleanup,
            char[] master) throws Exception {
        try {
            if (!servers.find(server.id()).filter(server::equals).isPresent())
                throw new SecurityException("server identity changed");
            try (var store = servers.secrets().open(server.credentialMode(), master);
                    var remote = gateway.connect(server.endpoint(), servers.loadPassword(server, store),
                            servers.hostKeyVerifier(server, value -> false))) {
                var candidate = new gold.debug.windowstolinux.shared.linux.transfer.RemoteTaskCandidate(workspace,
                        task);
                var before = remote.inspectTaskCandidate(candidate);
                if (!cleanup)
                    return new AgentObservation(true, true, before);
                if (!remote.cleanupTaskCandidate(candidate).succeeded())
                    return new AgentObservation(false, false,
                            Map.of("candidate", workspace.candidateId(), "cleanup", "unverified"));
                var after = remote.inspectTaskCandidate(candidate);
                boolean cleared = "absent".equals(after.get("state")) && "no".equals(after.get("buildActive"));
                var facts = new LinkedHashMap<>(after);
                facts.put("changed", Boolean.toString(cleared && !before.equals(after)));
                return new AgentObservation(cleared, cleared, facts);
            }
        } finally {
            Arrays.fill(master, '\0');
        }
    }
}
