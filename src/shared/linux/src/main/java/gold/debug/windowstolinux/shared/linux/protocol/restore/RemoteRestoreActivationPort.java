package gold.debug.windowstolinux.shared.linux.protocol.restore;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Fixed Linux capability for one staged restore activation transaction. / 单个已暂存恢复激活事务的固定 Linux 能力。
 */
public interface RemoteRestoreActivationPort {
    /**
     * Reads occupied TCP ports and managed-root facts without mutation. / 只读采集已占用 TCP 端口及受管根事实。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param requiredBytes required bytes / 必需字节
     * @return occupied TCP ports and managed-root facts without mutation / 只读采集已占用 TCP 端口及受管根事实
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    PreflightEvidence inspectRestoreActivation(String applicationId, long requiredBytes) throws LinuxOperationException;

    /**
     * Extracts and starts either isolated candidates or one tentative short-stop graph. / 提取并启动隔离候选或一个暂定短停机图。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return and starts either isolated candidates or one tentative short-stop graph / 并启动隔离候选或一个暂定短停机图
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    StepEvidence startRestoreActivation(RemoteRestoreActivationRequest request) throws LinuxOperationException;

    /**
     * Stops candidates and the old graph, then records a verified stopped-write boundary. / 停止候选与旧图并记录已验证停写边界。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved step evidence / 构造或解析得到的步骤证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    StepEvidence prepareRestoreCommit(RemoteRestoreActivationRequest request) throws LinuxOperationException;

    /**
     * Installs and starts the restored graph on its formal ports after database activation. / 数据库激活后在正式端口安装并启动恢复图。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved step evidence / 构造或解析得到的步骤证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    StepEvidence startRestoreFormal(RemoteRestoreActivationRequest request) throws LinuxOperationException;

    /**
     * Verifies every candidate component in dependency order. / 按依赖顺序验证每个候选组件。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved step evidence / 构造或解析得到的步骤证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    StepEvidence verifyRestoreComponents(RemoteRestoreActivationRequest request) throws LinuxOperationException;

    /**
     * Verifies the candidate whole-application health gate. / 验证候选整应用健康门。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved step evidence / 构造或解析得到的步骤证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    StepEvidence verifyRestoreApplication(RemoteRestoreActivationRequest request) throws LinuxOperationException;

    /**
     * Commits formal ports and re-verifies component and application health. / 提交正式端口并重新验证组件及整应用健康。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved commit evidence / 构造或解析得到的提交证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    CommitEvidence commitRestoreActivation(RemoteRestoreActivationRequest request) throws LinuxOperationException;

    /**
     * Stops every candidate/new process before database and release rollback. / 在数据库及发布回滚前停止全部候选和新进程。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved step evidence / 构造或解析得到的步骤证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    StepEvidence quiesceRestoreRecovery(RemoteRestoreActivationRequest request) throws LinuxOperationException;

    /**
     * Removes candidate effects and verifies the exact previous graph. / 移除候选影响并验证精确旧图。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved recovery evidence / 构造或解析得到的恢复证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    RecoveryEvidence recoverRestoreActivation(RemoteRestoreActivationRequest request) throws LinuxOperationException;

    /**
     * Read-only target facts. / 只读目标事实。
     *
     * @param managedRootWritable managed root writable / 受管根目录可写
     * @param foreignApplicationConflict foreign application conflict / 外部应用冲突
     * @param availableBytes available bytes / 可用字节
     * @param occupiedTcpPorts occupied tcp ports / occupiedTcp端口集合
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @param occupiedUdpPorts occupied udp ports / occupiedUdp端口集合
     */
    record PreflightEvidence(boolean managedRootWritable, boolean foreignApplicationConflict,
                             long availableBytes, Set<Integer> occupiedTcpPorts, List<String> evidence, Set<Integer> occupiedUdpPorts) {
        /**
         * Initializes preflight evidence through its shared constructor contract.
         * <p>通过共享构造契约初始化预检证据。
         *
         * @param writable writable / 可写
         * @param conflict conflict / 冲突
         * @param bytes content buffer processed by the current codec or stream / 当前编解码器或流处理的内容缓冲区
         * @param tcp tcp / tcp 对应的输入或状态
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         */
        public PreflightEvidence(boolean writable, boolean conflict, long bytes, Set<Integer> tcp, List<String> evidence) {
            this(writable, conflict, bytes, tcp, evidence, Set.of());
        }
        /**
         * Validates bounded target evidence. / 校验有界目标证据。
         *
         * @param managedRootWritable managed root writable / 受管根目录可写
         * @param foreignApplicationConflict foreign application conflict / 外部应用冲突
         * @param availableBytes available bytes / 可用字节
         * @param occupiedTcpPorts occupied tcp ports / occupiedTcp端口集合
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         * @param occupiedUdpPorts occupied udp ports / occupiedUdp端口集合
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public PreflightEvidence {
            if (availableBytes < 0) throw new IllegalArgumentException("availableBytes is invalid");
            occupiedTcpPorts = Set.copyOf(Objects.requireNonNull(occupiedTcpPorts, "occupiedTcpPorts"));
            if (occupiedTcpPorts.stream().anyMatch(port -> port == null || port < 1 || port > 65535)) {
                throw new IllegalArgumentException("occupiedTcpPorts is invalid");
            }
            occupiedUdpPorts = Set.copyOf(occupiedUdpPorts);
            if (occupiedUdpPorts.stream().anyMatch(port -> port < 1 || port > 65535)) throw new IllegalArgumentException("invalid UDP port");
            evidence = checked(evidence);
        }
    }

    /**
     * One activation or health step. / 单个激活或健康步骤。
     *
     * @param completed completed / 已完成
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    record StepEvidence(boolean completed, List<String> evidence) {
        /**
         * Validates bounded evidence. / 校验有界证据。
         *
         * @param completed completed / 已完成
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         */
        public StepEvidence { evidence = checked(evidence); }
    }

    /**
     * Final formal activation evidence. / 最终正式激活证据。
     *
     * @param committed committed / 已提交
     * @param previousReleaseRetained previous release retained / 此前发布已保留
     * @param formalComponentsHealthy formal components healthy / 正式组件集合健康
     * @param formalApplicationHealthy formal application healthy / 正式应用健康
     * @param activeReleaseToken active release token / 活跃发布令牌
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    record CommitEvidence(boolean committed, boolean previousReleaseRetained,
                          boolean formalComponentsHealthy, boolean formalApplicationHealthy,
                          String activeReleaseToken, List<String> evidence) {
        /**
         * Validates the formal result. / 校验正式结果。
         *
         * @param committed committed / 已提交
         * @param previousReleaseRetained previous release retained / 此前发布已保留
         * @param formalComponentsHealthy formal components healthy / 正式组件集合健康
         * @param formalApplicationHealthy formal application healthy / 正式应用健康
         * @param activeReleaseToken active release token / 活跃发布令牌
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public CommitEvidence {
            activeReleaseToken = Objects.requireNonNull(activeReleaseToken, "activeReleaseToken").trim();
            if (!activeReleaseToken.matches("[a-z0-9][a-z0-9-]{0,79}")) {
                throw new IllegalArgumentException("activeReleaseToken is invalid");
            }
            evidence = checked(evidence);
        }
    }

    /**
     * Candidate cleanup and old-graph verification. / 候选清理及旧图验证。
     *
     * @param candidateRemoved candidate removed / 候选已移除
     * @param previousGraphVerified previous graph verified / 此前图已验证
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    record RecoveryEvidence(boolean candidateRemoved, boolean previousGraphVerified, List<String> evidence) {
        /**
         * Validates bounded evidence. / 校验有界证据。
         *
         * @param candidateRemoved candidate removed / 候选已移除
         * @param previousGraphVerified previous graph verified / 此前图已验证
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         */
        public RecoveryEvidence { evidence = checked(evidence); }
    }

    /**
     * Validates supplied content before returning it to the next stage.
     * <p>在将所提供内容返回给下一阶段前完成校验。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static List<String> checked(List<String> values) {
        values = List.copyOf(Objects.requireNonNull(values, "evidence"));
        if (values.isEmpty() || values.size() > 64 || values.stream().anyMatch(value -> value == null
                || value.isBlank() || value.length() > 512 || value.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("restore activation evidence is invalid");
        }
        return values;
    }
}
