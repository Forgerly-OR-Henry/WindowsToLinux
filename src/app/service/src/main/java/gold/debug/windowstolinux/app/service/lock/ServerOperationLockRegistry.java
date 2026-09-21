package gold.debug.windowstolinux.app.service.lock;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Shares one reentrant operation lock per server identity inside this desktop process.
 * <p>在当前桌面进程中按服务器身份共享可重入操作锁。
 */
public final class ServerOperationLockRegistry {
    /**
     * Shared operation locks indexed by target identity.
     * <p>按目标身份索引的共享操作锁。
     */
    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * Returns the shared reentrant lock for a server identifier, creating it atomically on first use.
     * <p>返回服务器标识对应的共享可重入锁，并在首次使用时原子创建。
     *
     * @param serverId persisted server identifier / 持久化服务器标识
     * @return the operation result / 操作结果
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ReentrantLock forServer(String serverId) {
        return locks.computeIfAbsent(Objects.requireNonNull(serverId, "serverId"), ignored -> new ReentrantLock());
    }
}
