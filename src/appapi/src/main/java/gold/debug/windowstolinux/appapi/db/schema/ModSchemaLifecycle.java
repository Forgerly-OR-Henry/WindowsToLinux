package gold.debug.windowstolinux.appapi.db.schema;

/**
 * Author initialization and read-only validation before business startup. / 业务启动前由作者实现的初始化和只读验证。
 */
public interface ModSchemaLifecycle {

    /**
     * Initializes only the provided fresh target before business startup. / 仅初始化给定的新目标，不启动业务。
     * @param target owned target of the requested operation / 请求操作的所属目标
     * @throws Exception if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    void initialize(SchemaAccess target) throws Exception;

    /**
     * Reads the target and reports business invariants without modifying it. / 只读核对目标的业务不变量并返回结果。
     * @param target owned target of the requested operation / 请求操作的所属目标
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws Exception if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    CheckResult validate(SchemaAccess target) throws Exception;
}
