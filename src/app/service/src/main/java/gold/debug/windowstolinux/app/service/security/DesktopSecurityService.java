package gold.debug.windowstolinux.app.service.security;

import java.util.*;

import gold.debug.windowstolinux.app.db.persistence.repository.SecurityRepository;
import gold.debug.windowstolinux.app.secret.*;
import gold.debug.windowstolinux.app.service.contract.result.*;
import gold.debug.windowstolinux.app.service.contract.security.DesktopSecurityState;
import gold.debug.windowstolinux.app.service.contract.security.SecurityApplicationFacade;

/** Owns session authorization, accepted task leases and exclusive security changes. / 持有会话授权、已接纳任务句柄及独占安全变更。 */
public final class DesktopSecurityService implements SecurityApplicationFacade, AutoCloseable {
    /** Credential vault that owns key material. / 持有密钥材料的凭据库。
     */
    /** Native privacy participants registered by accepted recovery sessions. / 已接纳救援会话登记的原生隐私参与者。 */
    private final Set<java.util.function.Consumer<Boolean>> privacyParticipants = new LinkedHashSet<>();

    /** Credential vault that owns key material. / 持有密钥材料的凭据库。
     */
    private final CredentialVault vault;

    /** Current input or accepted operation context. / 当前输入或已接纳操作上下文。
     */
    private final ThreadLocal<Task> current = new ThreadLocal<>();

    /** Interactive key handle, absent while locked. / 交互密钥句柄，锁定时为空。
     */
    private CredentialVault.Session session;

    /** Current global credential encryption flag. / 当前全局凭据加密标记。
     */
    /** Exclusive security mutation reservation. / 独占安全变更预留。
     */
    /** Whether the first security profile exists. / 首次安全配置是否存在。
     */
    /** Whether this resource has been released. / 当前资源是否已释放。
     */
    private boolean configured, encrypted, changing, closed;

    /** Number of accepted operations retaining authorization. / 仍保留授权的已接纳操作数。
     */
    private final java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger();

    /** Nonblocking counts of retained tasks whose current key scope is absent. / 尚无当前密钥作用域的保留任务的非阻塞计数。 */
    private final Set<java.util.function.IntSupplier> retainedTasks = new LinkedHashSet<>();

    /** Lock generation used to reject stale human responses. / 用于拒绝过期人工响应的锁定代次。
     */
    private long generation;

    /** Binds the validated inputs and owned state for this scope. / 绑定当前作用域的已验证输入及持有状态。
     * @param repository repository supplied to this boundary / 传入当前边界的repository值
     */
    public DesktopSecurityService(SecurityRepository repository) {
        vault = new CredentialVault(repository);
        try {
            configured = vault.configured();
            encrypted = vault.encryptionEnabled();
        } catch (SecretStoreException failure) {
            throw ApplicationServiceException.create(ApplicationServiceFailureType.SECURITY_UNAVAILABLE,
                    "desktop security is unavailable", failure);
        }
    }

    /** Reports configuration, lock and accepted-task state without secrets. / 返回不含秘密的配置、锁定及已接纳任务状态。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    @Override
    public synchronized DesktopSecurityState securityState() {
        return new DesktopSecurityState(configured, session != null, encrypted, activeTasks(), changing);
    }

    /**
     * Tracks unfinished task identities across restarts and finalization failures. / 跟踪跨重启及收尾失败的未结束任务身份。
     * @param count nonblocking in-memory count, never acquiring task or database locks / 不取得任务或数据库锁的非阻塞内存计数
     * @return registration removed only after its task runtime drains / 仅在任务执行器排空后移除的登记
     */
    public synchronized AutoCloseable trackRetainedTasks(java.util.function.IntSupplier count) {
        if (closed)
            throw denied("security is closed");
        retainedTasks.add(Objects.requireNonNull(count));
        return () -> {
            synchronized (DesktopSecurityService.this) {
                retainedTasks.remove(count);
            }
        };
    }

    /** Returns active key scopes plus tasks still needing authorization or reconciliation. / 返回活动密钥作用域及仍需授权或核对的任务总数。
     * @return bounded unfinished-operation count / 有界未结束操作数 */
    private int activeTasks() {
        long total = active.get();
        for (var count : retainedTasks)
            total += Math.max(0, count.getAsInt());
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /** Creates the first security profile and unlocks only if no newer lock intervened. / 创建首次安全配置，仅在没有后续锁定时解锁。
     * @param password master-password buffer cleared after use / 使用后清除的主密码缓冲区
     * @param confirmation repeated password or confirmation callback / 重复密码或确认回调
     * @throws SecretStoreException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    @Override
    public void setupAndUnlock(char[] password, char[] confirmation) throws SecretStoreException {
        try {
            long expected;
            synchronized (this) {
                if (configured || changing || closed)
                    throw denied("security setup unavailable");
                changing = true;
                expected = generation;
            }
            CredentialVault.Session created = null;
            try {
                same(password, confirmation);
                minimumPassword(password);
                created = vault.initialize(password);
                synchronized (this) {
                    configured = true;
                    encrypted = true;
                    if (!closed && generation == expected) {
                        notifyPrivacy(false);
                        session = created;
                        created = null;
                    }
                    notifyAll();
                }
            } finally {
                if (created != null)
                    created.close();
                synchronized (this) {
                    changing = false;
                    notifyAll();
                }
            }
        } finally {
            clear(password);
            clear(confirmation);
        }
    }

    /** Verifies the password and opens a session without exposing the data key. / 验证密码并建立会话，不暴露数据密钥。
     * @param password master-password buffer cleared after use / 使用后清除的主密码缓冲区
     * @throws SecretStoreException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    @Override
    public void unlock(char[] password) throws SecretStoreException {
        try {
            long expected;
            synchronized (this) {
                if (!configured || changing || closed)
                    throw denied("unlock unavailable");
                changing = true;
                expected = generation;
            }
            CredentialVault.Session opened = null, old = null;
            try {
                opened = vault.unlock(password);
                synchronized (this) {
                    if (!closed && generation == expected) {
                        notifyPrivacy(false);
                        old = session;
                        session = opened;
                        opened = null;
                    }
                    notifyAll();
                }
            } finally {
                if (opened != null)
                    opened.close();
                if (old != null)
                    old.close();
                synchronized (this) {
                    changing = false;
                    notifyAll();
                }
            }
        } finally {
            clear(password);
        }
    }

    /** Drops interactive access while preserving accepted task capabilities. / 释放交互会话，保留已接纳任务的凭据能力。
     */
    @Override
    public void lock() {
        CredentialVault.Session old;
        synchronized (this) {
            notifyPrivacy(true);
            old = session;
            session = null;
            generation++;
            notifyAll();
        }
        if (old != null)
            old.close();
    }

    /** Verifies the current password and rewraps the unchanged data key. / 验证当前密码并重新包装原有数据密钥。
     * @param old current master-password buffer / 当前主密码缓冲区
     * @param replacement new master-password buffer / 新主密码缓冲区
     * @param confirmation repeated password or confirmation callback / 重复密码或确认回调
     * @throws SecretStoreException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    @Override
    public void changeMasterPassword(char[] old, char[] replacement, char[] confirmation) throws SecretStoreException {
        try {
            beginChange();
            try {
                same(replacement, confirmation);
                minimumPassword(replacement);
                vault.changePassword(old, replacement);
            } finally {
                endChange();
            }
        } finally {
            clear(old);
            clear(replacement);
            clear(confirmation);
        }
    }

    /** Exclusively converts all credentials and the global setting in one transaction. / 独占地在同一事务转换全部凭据及全局设置。
     * @param password master-password buffer cleared after use / 使用后清除的主密码缓冲区
     * @param enabled requested enabled flag / 请求启用标记
     * @throws SecretStoreException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    @Override
    public void setCredentialEncryption(char[] password, boolean enabled) throws SecretStoreException {
        try {
            beginChange();
            try {
                vault.setEncryption(password, enabled);
                synchronized (this) {
                    encrypted = enabled;
                }
            } finally {
                endChange();
            }
        } finally {
            clear(password);
        }
    }

    /** Reserves security mutation only while unlocked and without active tasks. / 仅在解锁且没有活动任务时预留安全变更。
     */
    private synchronized void beginChange() {
        requireUnlocked();
        if (activeTasks() != 0)
            throw ApplicationServiceException.create(ApplicationServiceFailureType.SECURITY_BUSY,
                    "active tasks prevent security changes");
        changing = true;
    }

    /** Releases the security mutation reservation and wakes waiting interactions. / 释放安全变更预留并唤醒等待交互。
     */
    private synchronized void endChange() {
        changing = false;
        notifyAll();
    }

    /** Serializes a short manual control action against locking and security changes. / 使短暂的人工控制动作与锁定、安全变更互斥。
     * @param operation operation supplied to this boundary / 传入当前边界的operation值
     * @param <T> t supplied to this boundary / 传入当前边界的T值
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    public synchronized <T> T whileUnlocked(java.util.function.Supplier<T> operation) {
        requireUnlocked();
        return operation.get();
    }

    /** Rejects interactive access during lock, shutdown or a security change. / 在锁定、关闭或安全变更时拒绝交互访问。
     */
    public synchronized void requireUnlocked() {
        if (session == null || changing || closed)
            throw denied("desktop is locked or changing security settings");
    }

    /** Uses the accepted task context or acquires a new interactive operation scope. / 使用已接纳任务上下文，或取得新的交互操作作用域。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    public Operation enterOperation() {
        Task task = current.get();
        if (task != null) {
            task.requireOpen();
            return new Operation(null);
        }
        synchronized (this) {
            requireUnlocked();
            task = new Task(session.fork());
            active.incrementAndGet();
        }
        current.set(task);
        return new Operation(task);
    }

    /** Atomically captures authorization before scheduling a background task. / 在调度后台任务前原子取得授权。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    @Override
    public synchronized Task beginTask() {
        requireUnlocked();
        Task task = new Task(session.fork());
        active.incrementAndGet();
        return task;
    }

    /** Gates confirmation callbacks until unlocked and rechecks the lock generation. / 门控确认回调直至解锁，并复核锁定代次。
     * @param confirmation repeated password or confirmation callback / 重复密码或确认回调
     * @param <T> t supplied to this boundary / 传入当前边界的T值
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    public <T> java.util.function.Predicate<T> confirm(java.util.function.Predicate<T> confirmation) {
        return value -> human(() -> confirmation.test(value));
    }

    /** Wraps human input callbacks in the common unlock gate. / 将人工输入回调接入统一解锁门禁。
     * @param type type supplied to this boundary / 传入当前边界的type值
     * @param delegate delegate supplied to this boundary / 传入当前边界的delegate值
     * @param <T> t supplied to this boundary / 传入当前边界的T值
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    public <T> T interaction(Class<T> type, T delegate) {
        if (delegate == null)
            return null;
        java.util.Set<Class<?>> interfaces = new LinkedHashSet<>();
        interfaces.add(type);
        for (Class<?> owner = delegate.getClass(); owner != null; owner = owner.getSuperclass())
            for (Class<?> contract : owner.getInterfaces())
                if (java.lang.reflect.Modifier.isPublic(contract.getModifiers()))
                    interfaces.add(contract);
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),
                interfaces.toArray(Class<?>[]::new), (proxy, method, args) -> human(() -> {
                    try {
                        return method.invoke(delegate, args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        if (failure.getCause() instanceof Exception cause)
                            throw cause;
                        if (failure.getCause() instanceof Error cause)
                            throw cause;
                        throw failure;
                    }
                })));
    }

    /** Waits for an unlocked session and discards responses crossing a lock boundary. / 等待解锁会话，丢弃跨越锁定边界的响应。
     * @param work operation executed within the retained boundary / 保留边界内执行的操作
     * @param <T> t supplied to this boundary / 传入当前边界的T值
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    private <T> T human(java.util.concurrent.Callable<T> work) {
        return human(work, () -> false);
    }

    /**
     * Gates task-owned interaction with cooperative cancellation and lock-generation checks. / 以协作取消及锁定代次检查门控任务所属交互。
     * @param <T> result type, including disposable secret characters / 结果类型，包括可清除秘密字符
     * @param work host-owned presentation callback / 宿主所属呈现回调
     * @param cancellation non-blocking current task cancellation / 非阻塞当前任务取消
     * @return result accepted within the same unlocked generation / 在同一解锁代次内接受的结果
     */
    public <T> T human(java.util.concurrent.Callable<T> work,
            gold.debug.windowstolinux.appapi.task.CancellationToken cancellation) {
        for (;;) {
            long expected;
            try {
                synchronized (this) {
                    while (!closed && (session == null || changing) && !cancellation.requested())
                        wait(50);
                    if (closed || cancellation.requested())
                        throw new java.util.concurrent.CancellationException("human interaction cancelled");
                    expected = generation;
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException("human interaction interrupted");
            }
            if (Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException("human interaction interrupted");
            T result;
            try {
                result = work.call();
            } catch (Exception failure) {
                synchronized (this) {
                    if (expected != generation)
                        continue;
                }
                if (failure instanceof RuntimeException runtime)
                    throw runtime;
                throw new IllegalStateException("human interaction failed", failure);
            }
            synchronized (this) {
                if (expected == generation && session != null && !changing && !cancellation.requested())
                    return result;
            }
            if (result instanceof char[] secret)
                clear(secret);
            if (cancellation.requested())
                throw new java.util.concurrent.CancellationException("human interaction cancelled");
        }
    }

    /** Forks the accepted operation capability for an asynchronous continuation. / 为异步延续复制已接纳操作的凭据能力。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    public Task captureTask() {
        Task parent = current.get();
        if (parent == null)
            throw denied("a background task needs an accepted operation");
        synchronized (this) {
            parent.requireOpen();
            active.incrementAndGet();
            return new Task(parent.credentials.fork());
        }
    }

    /** Forks a scoped credential handle from the current accepted task. / 从当前已接纳任务复制限定凭据句柄。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    public SecretStore openCredentials() {
        Task task = current.get();
        if (task == null)
            throw denied("credential access requires an accepted operation");
        task.requireOpen();
        return task.credentials.fork();
    }

    /** Waits interruptibly for an unlocked session outside security mutation. / 以可中断方式等待不处于安全变更的解锁会话。
     * @throws InterruptedException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    @Override
    public synchronized void awaitUnlocked() throws InterruptedException {
        while (!closed && (session == null || changing))
            wait();
        if (closed)
            throw new InterruptedException("desktop security closed");
    }

    /** Lexical authorization scope for a desktop operation. / 桌面操作的词法授权作用域。
     */
    public final class Operation implements AutoCloseable {
        /** Operation-owned task handle, absent in nested scopes. / 操作持有的任务句柄，嵌套作用域为空。
         */
        private final Task owned;
        /** Binds the validated inputs and owned state for this scope. / 绑定当前作用域的已验证输入及持有状态。
         * @param owned owned supplied to this boundary / 传入当前边界的owned值
         */
        private Operation(Task owned) {
            this.owned = owned;
        }

        /** Releases this scope and its owned resources. / 释放当前作用域及其持有资源。
         */
        @Override
        public void close() {
            if (owned != null) {
                current.remove();
                owned.close();
            }
        }
    }

    /** Retained authorization transferred explicitly to a background worker. / 显式传给后台线程的保留授权。 */
    public final class Task
            implements
                gold.debug.windowstolinux.app.service.contract.security.DesktopTaskAuthorization {
        /** Retained task credential handle. / 保留的任务凭据句柄。
         */
        private final CredentialVault.Session credentials;

        /** Close requested while executions may still be running. / 已请求关闭，执行可能尚未结束。
         */
        /** Whether the final task key release has occurred. / 是否已完成最终任务密钥释放。
         */
        private boolean closing, released;

        /** Number of executions still using this task authorization. / 仍使用任务授权的执行数。
         */
        private int executions;
        /** Binds the validated inputs and owned state for this scope. / 绑定当前作用域的已验证输入及持有状态。
         * @param credentials credentials supplied to this boundary / 传入当前边界的credentials值
         */
        private Task(CredentialVault.Session credentials) {
            this.credentials = credentials;
        }

        /** Runs work inside the retained authorization until execution cleanup finishes. / 在保留授权内执行工作，直至执行清理结束。
         * @param work operation executed within the retained boundary / 保留边界内执行的操作
         * @param <T> t supplied to this boundary / 传入当前边界的T值
         * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
         * @throws Exception if validation or the scoped operation fails / 校验或限定操作失败时
         */
        @Override
        public <T> T execute(java.util.concurrent.Callable<T> work) throws Exception {
            synchronized (this) {
                if (closing || released)
                    throw denied("task authorization is closed");
                executions++;
            }
            Task prior = current.get();
            current.set(this);
            try {
                return work.call();
            } finally {
                if (prior == null)
                    current.remove();
                else
                    current.set(prior);
                boolean release;
                synchronized (this) {
                    executions--;
                    release = markReleased();
                }
                if (release)
                    releaseCredentials();
            }
        }

        /** Runs a continuation under its retained task authorization. / 在保留的任务授权内执行延续操作。
         * @param work operation executed within the retained boundary / 保留边界内执行的操作
         */
        public void run(Runnable work) {
            try {
                execute(() -> {
                    work.run();
                    return null;
                });
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }

        /** Rejects access through a released capability. / 拒绝通过已释放的能力进行访问。
         */
        private synchronized void requireOpen() {
            if (released)
                throw denied("task authorization is closed");
        }

        /** Marks the final closed execution for one-time key release. / 标记最后一个已关闭执行，以便只释放一次密钥。
         * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
         */
        private boolean markReleased() {
            if (closing && executions == 0 && !released) {
                released = true;
                return true;
            }
            return false;
        }

        /** Releases the task key handle before reducing the active-task count. / 先释放任务密钥句柄，再减少活动任务计数。
        */
        private void releaseCredentials() {
            credentials.close();
            active.decrementAndGet();
        }

        /** Releases this scope and its owned resources. / 释放当前作用域及其持有资源。
         */
        @Override
        public void close() {
            boolean release;
            synchronized (this) {
                closing = true;
                release = markReleased();
            }
            if (release)
                releaseCredentials();
        }
    }

    /** Releases this scope and its owned resources. / 释放当前作用域及其持有资源。
     */
    /** Registers native windows under the same lock transition. / 在同一锁定转换中登记原生窗口。
     * @param participant applies native privacy state / 应用原生隐私状态
     * @return registration released after browser cleanup / 浏览器清理后释放的登记
     */
    public synchronized AutoCloseable observeLock(java.util.function.Consumer<Boolean> participant) {
        if (closed)
            throw denied("security is closed");
        participant.accept(session == null);
        privacyParticipants.add(participant);
        return () -> {
            synchronized (DesktopSecurityService.this) {
                privacyParticipants.remove(participant);
            }
        };
    }

    /** Applies native privacy before committing the session transition. / 在提交会话转换前应用原生隐私状态。
     * @param locked target lock state / 目标锁定状态
     */
    private void notifyPrivacy(boolean locked) {
        try {
            for (var participant : privacyParticipants)
                participant.accept(locked);
        } catch (RuntimeException failure) {
            for (var participant : privacyParticipants)
                try {
                    participant.accept(session == null);
                } catch (RuntimeException recovery) {
                    failure.addSuppressed(recovery);
                }
            throw ApplicationServiceException.create(ApplicationServiceFailureType.SECURITY_PRIVACY_FAILED,
                    "native recovery window privacy transition failed", failure);
        }
    }

    /** Releases this scope and its owned resources. / 释放当前作用域及其持有资源。
     */
    @Override
    public void close() {
        synchronized (this) {
            if (activeTasks() != 0 || changing)
                throw ApplicationServiceException.create(ApplicationServiceFailureType.SECURITY_BUSY,
                        "tasks and security changes must finish before security shutdown");
            closed = true;
        }
        lock();
    }

    /** Requires matching password entries before changing security state. / 要求两次密码输入一致后才能变更安全状态。
     * @param password master-password buffer cleared after use / 使用后清除的主密码缓冲区
     * @param confirmation repeated password or confirmation callback / 重复密码或确认回调
     */
    /** Rejects new passwords below the minimum length. / 拒绝长度不足的新主密码。
     * @param password new password input / 新密码输入
     */
    private static void minimumPassword(char[] password) {
        if (password.length < 8)
            throw ApplicationServiceException.create(ApplicationServiceFailureType.SECURITY_PASSWORD_INVALID,
                    "master password needs at least eight characters");
    }

    /** Requires matching password entries before changing security state. / 要求两次密码输入一致后才能变更安全状态。
     * @param password master-password buffer cleared after use / 使用后清除的主密码缓冲区
     * @param confirmation repeated password or confirmation callback / 重复密码或确认回调
     */
    private static void same(char[] password, char[] confirmation) {
        if (password == null || confirmation == null || !Arrays.equals(password, confirmation))
            throw ApplicationServiceException.create(ApplicationServiceFailureType.SECURITY_PASSWORD_MISMATCH,
                    "master password confirmation differs");
    }

    /** Overwrites the caller-owned sensitive buffer when present. / 覆盖调用方持有的非空敏感缓冲区。
     * @param value value supplied to this boundary / 传入当前边界的value值
     */
    private static void clear(char[] value) {
        if (value != null)
            Arrays.fill(value, '\0');
    }

    /** Creates a structured failure for unavailable interactive authorization. / 为不可用的交互授权创建结构化失败。
     * @param diagnostic diagnostic supplied to this boundary / 传入当前边界的diagnostic值
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    private static ApplicationServiceException denied(String diagnostic) {
        return ApplicationServiceException.create(ApplicationServiceFailureType.SECURITY_LOCKED, diagnostic);
    }
}
