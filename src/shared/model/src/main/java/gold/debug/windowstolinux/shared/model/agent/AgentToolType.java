package gold.debug.windowstolinux.shared.model.agent;

/** Closed catalog of typed deployment capabilities. / 封闭的类型化部署能力目录。 */
public enum AgentToolType {
    /** Analyze frozen source locally. / 本地分析冻结源码。 */
    ANALYZE_SOURCE,
    /** Inspect the selected server capabilities. / 检查所选服务器能力。 */
    VERIFY_SERVER,
    /** Apply the existing environment preparation plan. / 应用既有环境准备计划。 */
    PREPARE_ENVIRONMENT,
    /** Prepare reviewed declared databases. / 准备已审阅的声明数据库。 */
    PREPARE_DATABASE,
    /** Build, check, publish or roll back through the existing transaction. / 通过既有事务构建、检查、发布或回滚。 */
    DEPLOY_TRANSACTION,
    /** Read one owned service status. / 读取一个受管服务状态。 */
    SERVICE_STATUS,
    /** Read bounded sanitized service logs. / 读取有界脱敏服务日志。 */
    SERVICE_LOGS,
    /** Restart an owned service. / 重启一个受管服务。 */
    RESTART_SERVICE,
    /** Clean a task-owned candidate through the existing helper. / 通过既有助手清理任务所属候选。 */
    CLEAN_CANDIDATE,
    /** Query a task-bound candidate and build process. / 查询任务绑定候选项及构建进程。 */
    CANDIDATE_STATUS,
    /** Execute one exact independently reviewed command. / 执行一个经独立审批的精确命令。 */
    EXECUTE_COMMAND,
    /** Apply a revision-bound patch to a Linux task copy. / 向 Linux 任务副本应用修订绑定补丁。 */
    PATCH_SOURCE;
    /** Identifies operations whose uncertain result may have changed the server. / 标识结果不确定时可能改变服务器的操作。
     * @return whether the operation writes remote state / 操作是否写入远端状态
     */
    public boolean modifiesServer() {
        return switch (this) {
            case ANALYZE_SOURCE, VERIFY_SERVER, SERVICE_STATUS, SERVICE_LOGS, CANDIDATE_STATUS -> false;
            default -> true;
        };
    }
}
