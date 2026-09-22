package gold.debug.windowstolinux.app.service.contract;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.deployment.single.DeploymentOutcome;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseBinding;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.git.GitSnapshotException;
import gold.debug.windowstolinux.shared.git.GitSourceRequest;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import gold.debug.windowstolinux.shared.standard.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.shared.standard.deploy.contract.ReviewedDeploymentRequest;

/**
 * Narrow application operations required by single-component deployment. / 单组件部署所需的窄应用操作。
 */
public interface DeploymentApplicationFacade {
    /**
     * Prepares reviewed source.
     * <p>准备已审阅源码。
     *
     * @param sourceDirectory the user-selected source directory / 用户选择的源码目录
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @return constructed or resolved reviewed source preparation / 构造或解析得到的已审阅源码准备
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    ReviewedSourcePreparation prepareReviewedSource(Path sourceDirectory, DeploymentProjectType projectType)
            throws IOException;

    /**
     * Prepares reviewed git source.
     * <p>准备已审阅Git源码。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @return constructed or resolved reviewed source preparation / 构造或解析得到的已审阅源码准备
     * @throws GitSnapshotException if the git snapshot boundary rejects the operation / Git快照边界拒绝当前操作时
     */
    ReviewedSourcePreparation prepareReviewedGitSource(GitSourceRequest request, DeploymentProjectType projectType)
            throws GitSnapshotException;

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
     * Creates reviewed deployment request.
     * <p>创建已审阅部署请求。
     *
     * @param preparation preparation / 准备
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param databaseBindings the reviewed database scope, or empty when it was not reviewed / 经审阅数据库范围；未审阅时为空
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param userAccessUrl the optional user-facing HTTP URL / 可选的用户访问 HTTP URL
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @param rootBuildConfirmed root build confirmed / 根目录构建已确认
     * @param containerDaemonRiskAccepted the explicit Docker daemon risk approval / 显式 Docker 守护进程风险批准
     * @param experimentalAdapterRiskAccepted the fresh experimental-adapter test-environment approval / 本次试验适配器测试环境批准
     * @return reviewed deployment request / 已审阅部署请求
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    ReviewedDeploymentRequest createReviewedDeploymentRequest(ReviewedSourcePreparation preparation,
            ServerIdentity server, ConfigurationSnapshot configuration, List<SecretReference> secretReferences,
            Optional<List<ManagedDatabaseBinding>> databaseBindings, DeploymentRuntimeSpecification runtime,
            Optional<UserAccessUrl> userAccessUrl, BuildLimitConfiguration limits, boolean rootBuildConfirmed,
            boolean containerDaemonRiskAccepted, boolean experimentalAdapterRiskAccepted) throws SQLException;

    /**
     * Creates a request whose database scope has not yet been reviewed. / 创建数据库范围尚未审阅的请求。
     *
     * @param preparation preparation / 准备
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param userAccessUrl the optional user-facing HTTP URL / 可选的用户访问 HTTP URL
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @param rootBuildConfirmed root build confirmed / 根目录构建已确认
     * @param containerDaemonRiskAccepted the explicit Docker daemon risk approval / 显式 Docker 守护进程风险批准
     * @param experimentalAdapterRiskAccepted the fresh experimental-adapter test-environment approval / 本次试验适配器测试环境批准
     * @return a request whose database scope has not yet been reviewed / 数据库范围尚未审阅的请求
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    default ReviewedDeploymentRequest createReviewedDeploymentRequest(ReviewedSourcePreparation preparation,
            ServerIdentity server, ConfigurationSnapshot configuration, List<SecretReference> secretReferences,
            DeploymentRuntimeSpecification runtime, Optional<UserAccessUrl> userAccessUrl,
            BuildLimitConfiguration limits, boolean rootBuildConfirmed, boolean containerDaemonRiskAccepted,
            boolean experimentalAdapterRiskAccepted) throws SQLException {
        return createReviewedDeploymentRequest(preparation, server, configuration, secretReferences, Optional.empty(),
                runtime, userAccessUrl, limits, rootBuildConfirmed, containerDaemonRiskAccepted,
                experimentalAdapterRiskAccepted);
    }

    /**
     * Constructs an executable deployment plan from a request that has passed review.
     * <p>根据已通过审阅的请求构建可执行部署计划。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return an executable deployment plan from a request that has passed review / 根据已通过审阅的请求构建可执行部署计划
     */
    ReviewedDeploymentPlan planDeployment(ReviewedDeploymentRequest request);

    /**
     * Persists deployment configuration snapshot.
     * <p>持久化部署配置快照。
     *
     * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    void saveDeploymentConfigurationSnapshot(ConfigurationSnapshot snapshot) throws SQLException;

    /**
     * Deploys reviewed with stored password.
     * <p>部署已审阅具有已存储密码。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @return constructed or resolved deployment outcome / 构造或解析得到的部署结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    DeploymentOutcome deployReviewedWithStoredPassword(ReviewedDeploymentRequest request, ServerProfile profile,
            CredentialStorageMode mode, char[] masterPassword, Predicate<String> confirmation)
            throws SecretStoreException, SQLException;

    /**
     * Persists deployment secret revision.
     * <p>持久化部署秘密修订。
     *
     * @param referenceInput reference input / 引用输入
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return constructed or resolved secret reference / 构造或解析得到的秘密引用
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
    SecretReference saveDeploymentSecretRevision(String referenceInput, CredentialStorageMode mode,
            char[] masterPassword, char[] value) throws SQLException, SecretStoreException;
}
