package gold.debug.windowstolinux.app.db.init;

/** Host-injected current-user protection, independent of the database implementation. / 宿主注入的当前用户保护能力，独立于数据库实现。 */
public interface DatabaseProtection {
    /**
     * Protects bootstrap bytes for this Windows user and machine. / 为本机当前 Windows 用户保护引导字节。
     * @param clear temporary plaintext cleared by the caller / 调用者负责清零的临时明文
     * @return protected bytes safe to persist / 可持久化的受保护字节
     * @throws Exception if operating-system protection fails / 操作系统保护失败时
     */
    byte[] protect(byte[] clear) throws Exception;

    /**
     * Restores bootstrap bytes; callers clear the returned material. / 还原引导字节；调用方负责清零返回材料。
     * @param protectedBytes current-user protected material / 当前用户保护的材料
     * @return short-lived plaintext / 短期明文
     * @throws Exception if the identity or protected bytes are invalid / 身份或受保护字节无效时
     */
    byte[] unprotect(byte[] protectedBytes) throws Exception;
}
