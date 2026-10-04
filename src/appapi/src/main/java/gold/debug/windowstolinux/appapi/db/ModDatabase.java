package gold.debug.windowstolinux.appapi.db;

import java.sql.Connection;
import java.sql.SQLException;

import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/**
 * Task-bound access to the active owned schema generation. / 绑定任务并访问当前所有者数据代次的连接能力。
 */
public interface ModDatabase {

    /**
     * Returns the actual bound identity, which caller input cannot replace. / 返回实际绑定身份，调用方输入不能替换该身份。
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    SchemaIdentity identity() throws ApiException;

    /**
     * Opens an independent managed JDBC connection; the author owns transactions and closing. / 打开独立受管 JDBC 连接；事务及关闭由作者负责。
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     * @throws SQLException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    Connection openConnection(TaskContext task) throws ApiException, SQLException;
}
