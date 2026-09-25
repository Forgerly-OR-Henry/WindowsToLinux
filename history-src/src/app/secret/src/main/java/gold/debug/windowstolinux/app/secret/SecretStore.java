package gold.debug.windowstolinux.app.secret;

import java.util.Optional;

/**
 * Platform credential boundary. Callers receive copies and must clear them after use.
 *
 *  <p>平台凭据边界。调用方收到副本，并且必须在使用后清除。
 */
public interface SecretStore extends AutoCloseable {
    /**
     * Stores data through {@code save}.
     *
     *  <p>通过 {@code save} 保存数据。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
    void save(String key, char[] value) throws SecretStoreException;

    /**
     * Returns the value produced by {@code read}.
     *
     *  <p>返回 {@code read} 生成的值。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return the optional operation result / 可选操作结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
    Optional<char[]> read(String key) throws SecretStoreException;

    /**
     * Deletes one exact application credential and reports whether it existed. / 删除一个精确应用凭据并报告其是否存在。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return true when deletes one exact application credential and reports whether it existed, false otherwise / 删除一个精确应用凭据并报告其是否存在时为 true，否则为 false
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
    boolean delete(String key) throws SecretStoreException;

    /** Atomically changes credentials and their database owners. / 原子更新凭据及其数据库归属。
     * @param work related writes / 相关写入
     * @throws SecretStoreException on credential failure / 凭据失败
     * @throws java.sql.SQLException on metadata failure / 元数据失败
     */
    default void transaction(StoreWork work) throws SecretStoreException, java.sql.SQLException {
        throw new UnsupportedOperationException("this store does not support atomic database writes");
    }

    /** Related credential and owner updates. / 相关凭据及归属更新。 */
    @FunctionalInterface
    interface StoreWork {
        /** Performs the related updates. / 执行相关更新。
         * @throws SecretStoreException on credential failure / 凭据失败
         * @throws java.sql.SQLException on metadata failure / 元数据失败
         */
        void run() throws SecretStoreException, java.sql.SQLException;
    }

    /**
     * Closes this resource. / 关闭此资源。
     */
    @Override
    void close();
}
