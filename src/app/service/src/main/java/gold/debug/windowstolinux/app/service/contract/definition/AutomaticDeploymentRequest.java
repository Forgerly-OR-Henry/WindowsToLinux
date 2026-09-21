package gold.debug.windowstolinux.app.service.contract.definition;

import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.shared.git.GitSourceRequest;
import java.nio.file.Path;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentAutomationMode;
import gold.debug.windowstolinux.shared.model.deployment.AgentApprovalMode;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable click-time source/server selection and non-secret advanced overrides. / 点击时固定的源码和服务器选择，以及非秘密高级选项。
 *
 * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
 * @param git Git source / Git 源码
 * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
 * @param overrides overrides / 覆盖项集合
 * @param automationMode AI participation policy / AI 参与策略
 * @param approvalMode human confirmation policy / 人工确认策略
 * @param taskId immutable click-time task identity / 点击时不可变任务身份
 */
public record AutomaticDeploymentRequest(Optional<Path> directory, Optional<GitSourceRequest> git,
                                         ServerProfile server, Map<String, String> overrides,
                                         DeploymentAutomationMode automationMode, AgentApprovalMode approvalMode,String taskId) {
    /** Defaults noninteractive callers to deterministic deployment. / 非交互调用方默认采用确定性部署。
     * @param directory local source / 本地源码
     * @param git Git source / Git 源码
     * @param server selected server / 所选服务器
     * @param overrides reviewed form values / 已审阅表单值
     */
    public AutomaticDeploymentRequest(Optional<Path> directory,Optional<GitSourceRequest> git,ServerProfile server,Map<String,String> overrides){
        this(directory,git,server,overrides,DeploymentAutomationMode.STATIC,AgentApprovalMode.AUTOMATIC,java.util.UUID.randomUUID().toString());
    }
    /** Freezes the selected automation policy with the source selection. / 将所选自动化策略与源码选择一起冻结。
     * @param mode deployment mode / 部署模式
     * @param approval human confirmation policy / 人工确认策略
     * @return immutable request / 不可变请求
     */
    public AutomaticDeploymentRequest withAutomation(DeploymentAutomationMode mode,AgentApprovalMode approval){return new AutomaticDeploymentRequest(directory,git,server,overrides,mode,approval,taskId);}
    /**
     * Requires exactly one source and freezes the input before background work. / 要求恰好一个源码来源，并在后台任务开始前冻结输入。
     *
     * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
     * @param git Git source / Git 源码
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param overrides overrides / 覆盖项集合
 * @param automationMode AI participation policy / AI 参与策略
 * @param approvalMode human confirmation policy / 人工确认策略
 * @param taskId immutable click-time task identity / 点击时不可变任务身份
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public AutomaticDeploymentRequest {
        Objects.requireNonNull(automationMode); Objects.requireNonNull(approvalMode);
        java.util.UUID.fromString(taskId);
        Objects.requireNonNull(directory); Objects.requireNonNull(git); Objects.requireNonNull(server);
        if (directory.isPresent() == git.isPresent()) throw new IllegalArgumentException("select exactly one source");
        overrides = Map.copyOf(overrides);
    }
}
