package gold.debug.windowstolinux.web.service.contract;

import java.io.IOException;
import java.util.*;

import gold.debug.windowstolinux.shared.backup.format.BackupConfigurationCodec;
import gold.debug.windowstolinux.shared.backup.format.BackupConfigurationDocument;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.standard.deploy.input.AutomaticRuntimeResolver;

/**
 * Persists exact non-secret component facts for lifecycle operations and subsequent backups.
 * <p>持久化精确的非秘密组件事实，用于生命周期操作及后续备份。
 *
 * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
 * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
 * @param inputs reviewed non-secret deployment input fields / 已审阅的非秘密部署输入字段
 * @param activation activation / 激活
 * @param dependencies component identifiers that must precede this component / 必须先于当前组件执行的组件标识
 * @param releaseIdentity digest identifying the exact published release / 标识精确已发布版本的摘要
 * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
 * @param runtimeDefinition runtime definition / 运行时定义
 */
public record ManagedWebComponent(String id, ManagedApplication application, Map<String, String> inputs,
        String activation, List<String> dependencies, String releaseIdentity,
        List<gold.debug.windowstolinux.shared.config.secretref.SecretReference> secrets, String runtimeDefinition) {
    /**
     * Binds the supplied dependencies and state for managed web component.
     * <p>为受管Web组件绑定传入的依赖及状态。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param inputs reviewed non-secret deployment input fields / 已审阅的非秘密部署输入字段
     * @param activation activation / 激活
     * @param dependencies component identifiers that must precede this component / 必须先于当前组件执行的组件标识
     * @param releaseIdentity digest identifying the exact published release / 标识精确已发布版本的摘要
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @param runtimeDefinition runtime definition / 运行时定义
     */
    public ManagedWebComponent {
        inputs = Map.copyOf(inputs);
        dependencies = List.copyOf(dependencies);
        secrets = List.copyOf(secrets);
    }

    /**
     * Builds deployment runtime specification from the supplied runtime inputs.
     * <p>根据所提供运行时输入构建部署运行时规格。
     *
     * @return deployment runtime specification from the supplied runtime inputs / 根据所提供运行时输入构建部署运行时规格
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    public DeploymentRuntimeSpecification runtime() {
        try {
            return new gold.debug.windowstolinux.shared.config.persistence.serialization.DeploymentRuntimePersistenceCodec()
                    .read(Base64.getDecoder().decode(runtimeDefinition),
                            configuration().runtimeConfiguration().healthCheck());
        } catch (IOException failure) {
            throw new IllegalStateException("Stored runtime is invalid", failure);
        }
    }

    /**
     * Builds backup configuration document from the supplied configuration inputs.
     * <p>根据所提供配置输入构建备份配置文档。
     *
     * @return backup configuration document from the supplied configuration inputs / 根据所提供配置输入构建备份配置文档
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public BackupConfigurationDocument configuration() throws IOException {
        return new BackupConfigurationCodec().readActivation(Base64.getDecoder().decode(activation));
    }

    /**
     * Builds managed web component from the supplied published inputs.
     * <p>根据所提供已发布输入构建受管Web组件。
     *
     * @param digest content identity used for independent verification / 独立验证所用的内容身份
     * @return managed web component from the supplied published inputs / 根据所提供已发布输入构建受管Web组件
     */
    public ManagedWebComponent published(String digest) {
        return new ManagedWebComponent(id, application, inputs, activation, dependencies, digest, secrets,
                runtimeDefinition);
    }
}
