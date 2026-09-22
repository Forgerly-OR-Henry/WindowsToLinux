package gold.debug.windowstolinux.app.service.contract;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.contract.definition.MultiComponentReviewInput;
import gold.debug.windowstolinux.app.service.deployment.multi.ManagedMultiComponentApplication;
import gold.debug.windowstolinux.app.service.deployment.multi.ReviewedMultiComponentApplication;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.source.PreparedMultiComponentSource;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.deploy.contract.ApplicationHealthGate;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.MultiComponentDeploymentResult;
import gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle.MultiComponentLifecycleResult;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import gold.debug.windowstolinux.shared.standard.analyze.component.ComponentAnalysisRequest;

/**
 * Narrow application operations required by whole-application deployment and lifecycle. / 整应用部署与生命周期所需的窄应用操作。
 */
public interface MultiComponentApplicationFacade {
    /**
     * Parses component analysis within the service boundary. / 在服务边界内解析组件分析输入。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return component analysis within the service boundary / 在服务边界内解析组件分析输入
     */
    ComponentAnalysisRequest parseComponentAnalysis(
            gold.debug.windowstolinux.app.service.contract.definition.ComponentFormInput input);

    /**
     * Parses review inputs against the analyzed managed identity. / 根据已分析受管身份解析审阅输入。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @param managedApplicationId managed application id / 受管应用标识
     * @param containerRisk container risk / 容器风险
     * @param experimentalRisk experimental risk / 实验性风险
     * @return review inputs against the analyzed managed identity / 根据已分析受管身份解析审阅输入
     */
    MultiComponentReviewInput parseComponentReview(
            gold.debug.windowstolinux.app.service.contract.definition.ComponentFormInput input,
            String managedApplicationId, boolean containerRisk, boolean experimentalRisk);

    /**
     * Prepares reviewed multi component source.
     * <p>准备已审阅多组件源码。
     *
     * @param applicationRoot application root / 应用根目录
     * @param applicationId managed application identifier / 受管应用标识
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @return constructed or resolved prepared multi component source / 构造或解析得到的已准备多组件源码
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    PreparedMultiComponentSource prepareReviewedMultiComponentSource(Path applicationRoot, String applicationId,
            List<ComponentAnalysisRequest> components) throws IOException;

    /**
     * Finds trusted server.
     * <p>查找已信任服务器。
     *
     * @param serverId persisted server identifier / 持久化服务器标识
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    Optional<ServerIdentity> findTrustedServer(String serverId) throws SQLException;

    /**
     * Creates reviewed multi component application.
     * <p>创建已审阅多组件应用。
     *
     * @param prepared prepared / 已准备
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param inputs reviewed non-secret deployment input fields / 已审阅的非秘密部署输入字段
     * @param applicationHealth caller-supplied whole-application health contract / 调用方提供的整应用健康契约
     * @return reviewed multi component application / 已审阅多组件应用
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    ReviewedMultiComponentApplication createReviewedMultiComponentApplication(PreparedMultiComponentSource prepared,
            ServerIdentity server, List<MultiComponentReviewInput> inputs, ApplicationHealthGate applicationHealth)
            throws SQLException;

    /**
     * Persists deployment configuration snapshot.
     * <p>持久化部署配置快照。
     *
     * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    void saveDeploymentConfigurationSnapshot(ConfigurationSnapshot snapshot) throws SQLException;

    /**
     * Deploys reviewed multi component with stored password.
     * <p>部署已审阅多组件具有已存储密码。
     *
     * @param review review / 审阅
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @return constructed or resolved multi component deployment result / 构造或解析得到的多组件部署结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    MultiComponentDeploymentResult deployReviewedMultiComponentWithStoredPassword(
            ReviewedMultiComponentApplication review, ServerProfile profile, CredentialStorageMode mode,
            char[] masterPassword, Predicate<String> confirmation) throws SecretStoreException, SQLException;

    /**
     * Finds managed multi component application.
     * <p>查找受管多组件应用。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    Optional<ManagedMultiComponentApplication> findManagedMultiComponentApplication(String applicationId)
            throws SQLException;

    /**
     * Executes managed multi component lifecycle with stored password.
     * <p>执行受管多组件生命周期具有已存储密码。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param targetComponentIds target component ids / 目标组件标识集合
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @return constructed or resolved multi component lifecycle result / 构造或解析得到的多组件生命周期结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    MultiComponentLifecycleResult executeManagedMultiComponentLifecycleWithStoredPassword(String applicationId,
            Set<String> targetComponentIds, LifecycleAction action, ServerProfile profile, CredentialStorageMode mode,
            char[] masterPassword) throws SecretStoreException, SQLException;
}
