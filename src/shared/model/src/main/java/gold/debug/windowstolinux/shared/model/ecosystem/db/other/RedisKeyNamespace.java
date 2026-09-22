package gold.debug.windowstolinux.shared.model.ecosystem.db.other;

/**
 * Application-owned Redis key prefixes, avoiding unrestricted access to a shared instance. / 应用专属 Redis 键前缀，避免不受限地访问共享实例。
 *
 * @param prefix prefix / 前缀
 */
public record RedisKeyNamespace(String prefix) {
    /**
     * Validates and binds the inputs required by redis key namespace.
     * <p>校验并绑定Redis键命名空间所需输入。
     *
     * @param prefix prefix / 前缀
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public RedisKeyNamespace {
        if (prefix == null || !prefix.matches("[a-z][a-z0-9_-]{0,62}:"))
            throw new IllegalArgumentException("Redis namespace must be an application prefix ending in a colon");
    }

    /**
     * Returns acl pattern.
     * <p>返回acl匹配模式。
     *
     * @return acl pattern / acl匹配模式
     */
    public String aclPattern() {
        return "~" + prefix + "*";
    }
}
