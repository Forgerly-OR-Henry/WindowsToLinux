package gold.debug.windowstolinux.appapi.db;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Operation-bound connections used during initialization and migration. / 初始化与迁移期间绑定操作的连接能力。
 */
public interface SchemaAccess {

    /**
     * Returns the actual bound identity, which caller input cannot replace. / 返回实际绑定身份，调用方输入不能替换该身份。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    SchemaIdentity identity();

    /**
     * Opens an independent managed JDBC connection; the author owns transactions and closing. / 打开独立受管 JDBC 连接；事务及关闭由作者负责。
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws SQLException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    Connection openConnection() throws SQLException;
}
