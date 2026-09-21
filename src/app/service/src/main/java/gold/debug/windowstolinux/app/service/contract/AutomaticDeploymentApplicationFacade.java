package gold.debug.windowstolinux.app.service.contract;

import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDatabasePreparation;

import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDeploymentInteraction;

import gold.debug.windowstolinux.app.service.contract.definition.*;


import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Desktop automatic deployment boundary; domain execution remains in existing reviewed use cases. / 桌面自动部署边界，领域执行仍由现有审阅用例负责。
 */
public interface AutomaticDeploymentApplicationFacade extends DeploymentApplicationFacade, MultiComponentApplicationFacade,
        ServerApplicationFacade, AiApplicationFacade {
    /** Checks model availability before deployment effects. / 在部署副作用前检查模型可用性。
     * @param mode selected deployment mode / 所选部署模式
     * @throws java.sql.SQLException if configuration cannot be read / 无法读取配置时
     */
    void requireDeploymentModels(gold.debug.windowstolinux.shared.model.deployment.DeploymentAutomationMode mode) throws java.sql.SQLException;

    /**
     * Identifies a directory or a Git URI within the existing source policy. / 在既有源码策略内识别目录或 Git URI。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return constructed or resolved deployment source input / 构造或解析得到的部署源码输入
     */
    DeploymentSourceInput identifyDeploymentSource(String value);
    /**
     * Parses typed source and deployment controls inside the service boundary. / 在服务边界内解析源码与部署控件。
     *
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return typed source and deployment controls inside the service boundary / 在服务边界内解析源码与部署控件
     */
    AutomaticDeploymentRequest createAutomaticDeploymentRequest(DeploymentSourceInput source,
            gold.debug.windowstolinux.app.service.server.ServerProfile server, DeploymentFormInput input);

    /**
     * Completes missing reviewed database inputs through the supplied deployment interaction.
     * <p>通过所提供部署交互补全已审阅数据库输入中的缺失项。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param applicationId managed application identifier / 受管应用标识
     * @param assessment the typed static assessment / 类型化静态评估
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return constructed or resolved assessment / 构造或解析得到的评估
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    gold.debug.windowstolinux.shared.analyze.ecosystem.db.DatabaseProjectInspector.Assessment completeAutomaticDatabaseInputs(
            java.nio.file.Path root, String applicationId,
            gold.debug.windowstolinux.shared.analyze.ecosystem.db.DatabaseProjectInspector.Assessment assessment,
            char[] master, AutomaticDeploymentInteraction interaction) throws Exception;

    /**
     * Prepares automatic databases.
     * <p>准备自动数据库集合。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param applicationId managed application identifier / 受管应用标识
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param assessment the typed static assessment / 类型化静态评估
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @param progress progress / 进度
     * @return constructed or resolved automatic database preparation / 构造或解析得到的自动数据库准备
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    AutomaticDatabasePreparation prepareAutomaticDatabases(java.nio.file.Path root, String applicationId,
            gold.debug.windowstolinux.app.service.server.ServerProfile server,
            gold.debug.windowstolinux.shared.analyze.ecosystem.db.DatabaseProjectInspector.Assessment assessment, char[] master,
            AutomaticDeploymentInteraction interaction, Predicate<String> fingerprint, Consumer<LocalizedMessage> progress) throws Exception;

    /**
     * Offers APP rescue only when a desktop interaction is available. / 仅在具有桌面交互时提供 APP 救援。
     *
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param operation operation / 操作
     * @return true when offers APP rescue only when a desktop interaction is available, false otherwise / 仅在具有桌面交互时提供 APP 救援时为 true，否则为 false
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    default boolean recoverSshForOperation(gold.debug.windowstolinux.app.service.server.ServerProfile server,
            char[] master, Predicate<String> fingerprint, AutomaticDeploymentInteraction interaction, String operation) throws Exception {
        java.util.Arrays.fill(master, '\0'); return false;
    }

    /**
     * Deploys automatically.
     * <p>部署自动。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param firstUseConfirmation first use confirmation / 首次使用确认
     * @param progress progress / 进度
     * @return constructed or resolved automatic deployment outcome / 构造或解析得到的自动部署结果
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    AutomaticDeploymentOutcome deployAutomatically(AutomaticDeploymentRequest request, char[] masterPassword,
            AutomaticDeploymentInteraction interaction, Predicate<String> firstUseConfirmation,
            Consumer<LocalizedMessage> progress) throws Exception;

    /**
     * Deploys automatically reviewed.
     * <p>部署自动已审阅。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @param progress progress / 进度
     * @return constructed or resolved deployment outcome / 构造或解析得到的部署结果
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    gold.debug.windowstolinux.app.service.deployment.single.DeploymentOutcome deployAutomaticallyReviewed(
            gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest request,
            gold.debug.windowstolinux.app.service.server.ServerProfile profile, char[] master,
            Predicate<String> fingerprint, Consumer<LocalizedMessage> progress) throws Exception;
    /**
     * Deploys automatically reviewed.
     * <p>部署自动已审阅。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @param progress progress / 进度
     * @return constructed or resolved multi component deployment result / 构造或解析得到的多组件部署结果
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    gold.debug.windowstolinux.shared.deploy.contract.result.deployment.MultiComponentDeploymentResult deployAutomaticallyReviewed(
            gold.debug.windowstolinux.app.service.deployment.multi.ReviewedMultiComponentApplication request,
            gold.debug.windowstolinux.app.service.server.ServerProfile profile, char[] master,
            Predicate<String> fingerprint, Consumer<LocalizedMessage> progress) throws Exception;
    /** Requests a safe task control transition. / 请求安全任务控制转换。
     * @param taskId active task identity / 活动任务身份
     * @param command requested transition / 请求转换
     */
    void controlDeployment(String taskId,gold.debug.windowstolinux.shared.model.agent.AgentTaskCommandAction command);

    /** Reads process-local state and pause reason. / 读取进程内状态及暂停原因。
     * @param taskId active task identity / 活动任务身份
     * @return nonsecret state / 非秘密状态
     */
    java.util.Map<String,String> deploymentTaskState(String taskId);

    /** Reads recent deployment task history. / 读取最近部署任务历史。
     * @return nonsecret records / 非秘密记录
     * @throws java.sql.SQLException on database failure / 数据库失败时
     */
    java.util.List<java.util.Map<String,String>> deploymentTaskHistory() throws java.sql.SQLException;

    /** Reads a task audit trail. / 读取任务审计轨迹。
     * @param taskId task identity / 任务身份
     * @return bounded audit events / 有界审计事件
     * @throws java.sql.SQLException on database failure / 数据库失败时
     */
    java.util.List<java.util.Map<String,String>> deploymentTaskEvents(String taskId) throws java.sql.SQLException;

}
