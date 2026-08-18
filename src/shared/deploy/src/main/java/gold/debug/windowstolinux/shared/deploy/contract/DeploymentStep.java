package gold.debug.windowstolinux.shared.deploy.contract;

/**
 * An auditable fixed stage of the typed deployment short-downtime deployment transaction.
 *
 * <p>部署短停机部署事务的可审计固定阶段。
 */
public enum DeploymentStep {
    /** Bind the exact source archive and optional Git commit. / 绑定精确源码归档和可选 Git Commit。 */
    VERIFY_SOURCE_IDENTITY,
    /** Validate the immutable normal configuration snapshot. / 验证不可变普通配置快照。 */
    VERIFY_CONFIGURATION_SNAPSHOT,
    /** Verify each referenced secret revision and its permissions. / 验证每个引用秘密修订及其权限。 */
    VERIFY_SECRET_REVISIONS,
    /** Prepare the isolated target-host candidate. / 准备隔离的目标机候选版本。 */
    PREPARE_CANDIDATE,
    /** Execute the type-specific fixed build entrypoint. / 执行类型专属固定构建入口。 */
    BUILD,
    /** Verify the expected typed artifact. / 验证预期的类型化产物。 */
    VERIFY_ARTIFACT,
    /** Verify static output stays below the declared output directory. / 验证静态输出位于声明的输出目录之下。 */
    VERIFY_STATIC_OUTPUT,
    /** Verify the single-container security policy. / 验证单容器安全策略。 */
    VERIFY_CONTAINER_POLICY,
    /** Stop and confirm the prior managed version. / 停止并确认先前受管版本。 */
    STOP_PREVIOUS,
    /** Activate exactly one candidate definition. / 激活恰好一个候选定义。 */
    ACTIVATE_CANDIDATE,
    /** Execute the layered health check. / 执行分层健康检查。 */
    CHECK_HEALTH,
    /** Commit the new version and referenced configuration. / 提交新版本及引用的配置。 */
    COMMIT_RELEASE,
    /** Restore the prior release and configuration on any failed post-stop step. / 任一停止后步骤失败时恢复先前版本和配置。 */
    ROLLBACK_ON_FAILURE
}
