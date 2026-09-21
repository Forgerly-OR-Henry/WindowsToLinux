package gold.debug.windowstolinux.app.service.ai;

import gold.debug.windowstolinux.app.db.persistence.repository.AiProfileRepository;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.server.DesktopSecretStoreService;
import gold.debug.windowstolinux.shared.ai.client.OpenAiCompatibleRoleClient;
import gold.debug.windowstolinux.shared.ai.collaboration.role.AiRoleBinding;
import gold.debug.windowstolinux.shared.ai.collaboration.role.AiRoleContext;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiRoleInvocationResult;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Coordinates grouped model configuration, verification and ordered role invocation.
 * <p>协调按组模型配置、验证和有序角色调用。
 */
public final class AiUseCaseFacade {
    /**
     * Bound ai profile repository collaborator for profiles.
     * <p>处理配置资料集合的AI配置资料仓库协作对象。
     */
    private final AiProfileRepository profiles;
    /**
     * Role client.
     * <p>角色客户端。
     */
    private final OpenAiCompatibleRoleClient roleClient;
    /**
     * Chain.
     * <p>调用链。
     */
    private final AiProviderChain chain;
    /**
     * Reviewed configuration snapshot or settings.
     * <p>已审阅配置快照或设置。
     */
    private final AiConfigurationUseCase configuration;
    /** Calls the deployment route at a fixed assisted checkpoint. / 在固定辅助节点调用部署路由。
     * @param phase fixed checkpoint / 固定节点
     * @param candidates nonsecret candidate values / 非秘密候选值
     * @param evidence observed nonsecret facts / 已观察非秘密事实
     * @param master unlock buffer / 解锁缓冲区
     * @return evidence-linked advice / 关联证据的建议
     * @throws SQLException when routing configuration cannot be read / 无法读取路由配置时
     */
    public gold.debug.windowstolinux.shared.model.deployment.AssistedDeploymentAdvice assist(String phase,java.util.Map<String,java.util.List<String>> candidates,
            java.util.Map<String,String> evidence,char[] master)throws SQLException{
        var client=new gold.debug.windowstolinux.shared.ai.client.AgentProtocolClient();
        var result=chain.invoke(master,(profile,key)->{var reply=client.assist(profile.chatCompletionsEndpoint(),profile.model(),key,phase,candidates,evidence);
            DeploymentAiScope.current().ifPresent(scope->scope.usage(reply.tokens()));
            return new AiProviderChain.Attempt<>(reply.value(),gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiInvocationStatus.VALIDATED,"assisted-advice-validated");});
        if(!result.valid())throw new IllegalStateException("deployment-models-unavailable");return result.value().orElseThrow();
    }
    /** Opens the independent Agent model ports within a frozen task scope. / 在冻结任务作用域内打开独立 Agent 模型端口。
     * @param master unlock buffer / 解锁缓冲区
     * @return task-owned adapter / 任务所属适配器
     */
    public DeploymentAgentModelAdapter agentModels(char[] master){return new DeploymentAgentModelAdapter(chain,new gold.debug.windowstolinux.shared.ai.client.AgentProtocolClient(),master);}
    /**
     * Tests visual understanding separately from the text save probe. / 独立于文字保存测试验证视觉理解。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @param capability separately tested text or vision capability / 分别测试的文本或视觉能力
     */
    public void testCapability(String id, gold.debug.windowstolinux.shared.model.ai.AiCapabilityType capability, char[] master) throws Exception { configuration.testCapability(id, capability, master); }

    /**
     * Initializes ai use case facade through its shared constructor contract.
     * <p>通过共享构造契约初始化AI用例门面。
     *
     * @param profiles profiles / 配置资料集合
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     */
    public AiUseCaseFacade(AiProfileRepository profiles, DesktopSecretStoreService secrets) {
        this(profiles, secrets, new OpenAiCompatibleRoleClient());
    }

    /**
     * Validates and binds the inputs required by ai use case facade.
     * <p>校验并绑定AI用例门面所需输入。
     *
     * @param profiles profiles / 配置资料集合
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @param roleClient role client / 角色客户端
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    AiUseCaseFacade(AiProfileRepository profiles, DesktopSecretStoreService secrets, OpenAiCompatibleRoleClient roleClient) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        Objects.requireNonNull(secrets, "secrets");
        this.roleClient = Objects.requireNonNull(roleClient, "roleClient");
        this.chain = new AiProviderChain(profiles, secrets);
        this.configuration = new AiConfigurationUseCase(profiles, secrets, roleClient);
    }

    /** Opens an immutable task scope after checking all required purposes. / 检查必需用途后打开不可变任务作用域。
     * @param mode requested deployment mode / 请求部署模式
     * @return worker-owned routing scope / 工作线程持有的路由作用域
     * @throws SQLException if configuration cannot be read / 无法读取配置时
     */
    public DeploymentAiScope openDeployment(gold.debug.windowstolinux.shared.model.deployment.DeploymentAutomationMode mode) throws SQLException {
        return new DeploymentAiScope(mode,deploymentSnapshot(mode));
    }
    /** Captures and validates all purpose lists atomically without invoking a model. / 原子捕获并验证全部用途列表，不调用模型。
     * @param mode requested mode / 请求模式
     * @return immutable verified routing snapshot / 不可变已验证路由快照
     * @throws SQLException on configuration read failure / 配置读取失败时
     */
    public java.util.Map<gold.debug.windowstolinux.shared.model.ai.AiPurposeType,java.util.List<AiProviderProfile>> deploymentSnapshot(gold.debug.windowstolinux.shared.model.deployment.DeploymentAutomationMode mode)throws SQLException{
        var snapshot=new java.util.EnumMap<gold.debug.windowstolinux.shared.model.ai.AiPurposeType,java.util.List<AiProviderProfile>>(gold.debug.windowstolinux.shared.model.ai.AiPurposeType.class);
        if(mode!=gold.debug.windowstolinux.shared.model.deployment.DeploymentAutomationMode.STATIC){
            profiles.purposeSnapshot().forEach((purpose,values)->snapshot.put(purpose,values.stream().map(v->AiProviderProfile.fromStored(v.profile())).toList()));
            if(mode==gold.debug.windowstolinux.shared.model.deployment.DeploymentAutomationMode.AGENT&&snapshot.get(gold.debug.windowstolinux.shared.model.ai.AiPurposeType.APPROVAL).isEmpty())
                throw gold.debug.windowstolinux.app.service.failure.ApplicationServiceException.create(gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType.APPROVAL_MODEL_REQUIRED,"No verified enabled approval model is configured");
            if(snapshot.get(gold.debug.windowstolinux.shared.model.ai.AiPurposeType.DEPLOYMENT).isEmpty())
                throw gold.debug.windowstolinux.app.service.failure.ApplicationServiceException.create(gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType.DEPLOYMENT_MODEL_REQUIRED,"No verified enabled deployment model is configured");
        }
        return java.util.Map.copyOf(snapshot);
    }

    /**
     * Lists ordered enablement and verification metadata. / 列出有序启用及验证元数据。
     *
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public List<AiProviderSummary> configurations() throws SQLException {
        return profiles.listConfigured().stream().map(value -> new AiProviderSummary(AiProviderProfile.fromStored(value.profile()),
                value.name(), value.priority(), value.revision(), value.textVerifiedAt(), value.visionVerifiedAt())).toList();
    }
    /**
     * Saves one category-specific probe. / 保存经过对应类别探测的模型。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param key API credential characters supplied to the selected operation / 提供给所选操作的 API 凭据字符
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @param capability verified text or vision capability / 已验证文本或视觉能力
     */
    public void saveConfiguration(AiProviderProfile profile, String name, char[] master, char[] key, gold.debug.windowstolinux.shared.model.ai.AiCapabilityType capability) throws SQLException, SecretStoreException { configuration.save(profile, name, master, key, capability); }
    /**
     * Reorders one category. / 调整一个类别的顺序。
     *
     * @param ids ids / 标识集合
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public void reorder(List<String> ids) throws SQLException { profiles.reorder(ids); }

    /** Reads ordered purpose members. / 读取有序用途成员。
     * @param purpose selected purpose / 所选用途
     * @return ordered members / 有序成员
     * @throws SQLException if reading fails / 读取失败时
     */
    public java.util.List<gold.debug.windowstolinux.shared.model.ai.AiPurposeAssignment> purpose(gold.debug.windowstolinux.shared.model.ai.AiPurposeType purpose) throws SQLException { return profiles.purposes().list(purpose); }
    /** Saves an entire purpose draft atomically. / 原子保存完整用途草稿。
     * @param purpose selected purpose / 所选用途
     * @param members ordered membership / 有序成员
     * @throws SQLException if saving fails / 保存失败时
     */
    public void savePurpose(gold.debug.windowstolinux.shared.model.ai.AiPurposeType purpose, java.util.List<gold.debug.windowstolinux.shared.model.ai.AiPurposeAssignment> members) throws SQLException { profiles.purposes().save(purpose,members); }


    /**
     * Preserves fixed role prompts and validators while trying enabled regular-group providers in captured priority order; historical role assignments do not select providers.
     * <p>保留固定角色提示及校验器，按捕获优先级顺序尝试已启用常规组提供者；历史角色分配不参与提供者选择。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @return validated advice or classified unavailable evidence; empty when no enabled provider exists / 已验证建议或分类不可用证据；没有已启用提供者时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public Optional<AiRoleInvocationResult> invokeRole(AiRoleContext context, char[] masterPassword)
            throws SQLException, SecretStoreException {
        Objects.requireNonNull(context, "context");
        var result = chain.invoke(masterPassword, (profile, key) -> {
            var value = roleClient.invoke(new AiRoleBinding(context.role(), profile.id(), profile.chatCompletionsEndpoint(), profile.model()), key, context);
            return new AiProviderChain.Attempt<>(value, value.evidence().status(), value.evidence().validationDetail());
        });
        if (result.snapshot().isEmpty()) return Optional.empty();
        var evidence = result.value().map(AiRoleInvocationResult::evidence).orElseGet(() -> {
            var last = result.snapshot().getLast();
            return roleClient.invoke(new AiRoleBinding(context.role(), last.id(), last.chatCompletionsEndpoint(), last.model()), new char[0], context).evidence();
        });
        if (!result.valid()) evidence = new gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiInvocationEvidence(evidence.role(), evidence.providerId(),
                evidence.model(), evidence.redactedInputSummary(), evidence.inputSha256(), gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiInvocationStatus.UNAVAILABLE,
                Optional.empty(), "all-enabled-providers-failed", evidence.observedAt());
        return Optional.of(new AiRoleInvocationResult(evidence, result.attempts()));
    }

}
