package gold.debug.windowstolinux.app.service.deployment.automatic;

import gold.debug.windowstolinux.app.db.entity.StoredApplicationSecretRevision;
import gold.debug.windowstolinux.app.db.persistence.repository.ApplicationSecretRepository;
import gold.debug.windowstolinux.app.service.config.DeploymentConfigurationUseCase;
import gold.debug.windowstolinux.app.service.contract.AiApplicationFacade;
import gold.debug.windowstolinux.app.service.server.*;
import gold.debug.windowstolinux.shared.analyze.ecosystem.db.DatabaseProjectInspector;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.deploy.contract.*;
import gold.debug.windowstolinux.shared.deploy.contract.spi.DatabaseCredentialPort;
import gold.debug.windowstolinux.shared.deploy.execution.environment.NativeDatabasePreparationService;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Adapts desktop credentials and user interaction to shared native database preparation.
 * <p>将桌面凭据及用户交互适配到共享原生数据库准备流程。
 */
public final class AutomaticDatabaseUseCase {
    /**
     * Bound server use case facade collaborator for server-profile and authenticated-session service.
     * <p>处理服务器资料及已认证会话服务的服务器用例门面协作对象。
     */
    private final ServerUseCaseFacade servers;
    /**
     * Factory for authenticated Linux sessions.
     * <p>已认证 Linux 会话的工厂。
     */
    private final DeploymentLinuxGateway gateway;
    /**
     * Bound application secret repository collaborator for secret metadata.
     * <p>处理秘密元数据的应用秘密仓库协作对象。
     */
    private final ApplicationSecretRepository secretMetadata;
    /**
     * Bound desktop secret store service collaborator for stores.
     * <p>处理存储集合的Desktop秘密存储服务协作对象。
     */
    private final DesktopSecretStoreService stores;
    /**
     * Configurations.
     * <p>配置集合。
     */
    private final DeploymentConfigurationUseCase configurations;
    /**
     * Completion.
     * <p>完成。
     */
    private final AutomaticInputCompletion completion;
    /**
     * Binds the supplied dependencies and state for automatic database use case.
     * <p>为自动数据库用例绑定传入的依赖及状态。
     *
     * @param servers server-profile and authenticated-session service / 服务器资料及已认证会话服务
     * @param gateway factory for authenticated Linux sessions / 已认证 Linux 会话的工厂
     * @param secretMetadata secret metadata / 秘密元数据
     * @param stores stores / 存储集合
     * @param configurations configurations / 配置集合
     * @param ai the supplied ai application facade / 所提供的AI应用门面
     */
    public AutomaticDatabaseUseCase(ServerUseCaseFacade servers, DeploymentLinuxGateway gateway,
            ApplicationSecretRepository secretMetadata, DesktopSecretStoreService stores,
            DeploymentConfigurationUseCase configurations, AiApplicationFacade ai) {
        this.servers=servers; this.gateway=gateway; this.secretMetadata=secretMetadata; this.stores=stores;
        this.configurations=configurations; this.completion=new AutomaticInputCompletion(ai);
    }
    /**
     * Builds assessment from the supplied complete inputs inputs.
     * <p>根据所提供完整输入集合输入构建评估。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param applicationId managed application identifier / 受管应用标识
     * @param assessment the typed static assessment / 类型化静态评估
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return assessment from the supplied complete inputs inputs / 根据所提供完整输入集合输入构建评估
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public DatabaseProjectInspector.Assessment completeInputs(Path root, String applicationId,
            DatabaseProjectInspector.Assessment assessment, char[] master, AutomaticDeploymentInteraction interaction) throws Exception {
        try { return new NativeDatabasePreparationService(null).completeInputs(root,applicationId,assessment,withAi(master,interaction)); }
        finally { Arrays.fill(master,'\0'); }
    }
    /**
     * Prepares automatic database preparation.
     * <p>准备自动数据库准备。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param applicationId managed application identifier / 受管应用标识
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param assessment the typed static assessment / 类型化静态评估
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @param progress progress / 进度
     * @return constructed or resolved automatic database preparation / 构造或解析得到的自动数据库准备
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public AutomaticDatabasePreparation prepare(Path root, String applicationId, ServerProfile profile,
            DatabaseProjectInspector.Assessment assessment, char[] master, AutomaticDeploymentInteraction interaction,
            Predicate<String> fingerprint, Consumer<LocalizedMessage> progress) throws Exception {
        try (var store = stores.open(profile.credentialMode(), master);
             var session = gateway.connect(profile.endpoint(),servers.loadPassword(profile,store),servers.hostKeyVerifier(profile,fingerprint))) {
            return new NativeDatabasePreparationService(credentials(profile,master)).prepare(root,applicationId,profile.id(),session.nativeDatabases(),assessment,interaction,progress);
        } finally { Arrays.fill(master,'\0'); }
    }
    /**
     * Returns the contract with the supplied ai applied.
     * <p>返回应用所提供AI后的契约。
     *
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param delegate delegate / 被委派对象
     * @return the contract with the supplied ai applied / 应用所提供AI后的契约
     */
    private AutomaticDeploymentInteraction withAi(char[] master, AutomaticDeploymentInteraction delegate) {
        if(gold.debug.windowstolinux.app.service.ai.DeploymentAiScope.current().isPresent())return delegate;
        return new AutomaticDeploymentInteraction() {
            /**
             * Resolves the requested non-secret fields through the existing completion and interaction services.
             * <p>通过既有补全及交互服务解析所请求的非秘密字段。
             *
             * @param fields allowed or requested input field definitions / 允许或请求的输入字段定义
             * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
             */
            @Override public Optional<Map<String,String>> requestInputs(List<DeploymentInputField> fields) { return Optional.of(completion.resolve(fields,master,delegate)); }
            /**
             * Confirms anonymous.
             * <p>确认匿名。
             *
             * @param key lookup key within the current contract / 当前契约内的查找键
             * @param details details / 详情
             * @return true when confirms anonymous, false otherwise / 确认匿名时为 true，否则为 false
             */
            @Override public boolean confirm(String key, Map<String,?> details) { return delegate.confirm(key,details); }
            /**
             * Confirms database replacement.
             * <p>确认数据库替换。
             *
             * @param details details / 详情
             * @return true when confirms database replacement, false otherwise / 确认数据库替换时为 true，否则为 false
             */
            @Override public boolean confirmDatabaseReplacement(Map<String,?> details) { return delegate.confirmDatabaseReplacement(details); }
            /**
             * Requests transient secret characters from the caller's interaction contract.
             * <p>通过调用方交互契约请求临时秘密字符。
             *
             * @param key lookup key within the current contract / 当前契约内的查找键
             * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
             */
            @Override public char[] requestSecret(String key) { return delegate.requestSecret(key); }
        };
    }
    /**
     * Builds database credential port from the supplied credentials inputs.
     * <p>根据所提供凭据输入构建数据库凭据端口。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @return database credential port from the supplied credentials inputs / 根据所提供凭据输入构建数据库凭据端口
     */
    private DatabaseCredentialPort credentials(ServerProfile profile,char[] master) {
        return new DatabaseCredentialPort() {
            /**
             * Finds the latest consecutively registered secret revision, or returns empty when revision one is absent.
             * <p>查找连续登记的最新秘密修订；修订一不存在时返回空值。
             *
             * @param identifier the stable secret identifier / 稳定的秘密标识
             * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
             * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
             */
            @Override public Optional<SecretReference> latest(String identifier) throws Exception {
                var reference = new SecretReference(identifier,1);
                if (secretMetadata.findRevision(reference).isEmpty()) return Optional.empty();
                while (secretMetadata.findRevision(new SecretReference(identifier,reference.revision()+1)).isPresent()) reference = new SecretReference(identifier,reference.revision()+1);
                return Optional.of(reference);
            }
            /**
             * Loads the exact application secret revision into caller-owned transient characters.
             * <p>将精确应用秘密修订加载为由调用方持有的临时字符。
             *
             * @param reference immutable public secret identity / 不可变公开秘密身份
             * @return caller-owned secret characters to clear after use / 调用方持有且须在使用后清空的秘密字符
             * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
             */
            @Override public char[] load(SecretReference reference) throws Exception {
                var stored = secretMetadata.findRevision(reference).orElseThrow();
                try (var store = stores.open(stored.credentialMode(),master)) { return store.read(stored.credentialKey()).orElseThrow(() -> new IllegalStateException("Saved DB credential is missing")); }
            }
            /**
             * Persists anonymous.
             * <p>持久化匿名。
             *
             * @param reference immutable public secret identity / 不可变公开秘密身份
             * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
             * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
             */
            @Override public void save(SecretReference reference,char[] value) throws Exception {
                String digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(reference.identifier().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                configurations.saveSecretRevision(new StoredApplicationSecretRevision(reference,"application-secret/"+digest+"/"+reference.revision(),profile.credentialMode(),Instant.now()),profile.credentialMode(),master.clone(),value);
            }
        };
    }
}
