package gold.debug.windowstolinux.app.db.persistence.connection;

import java.lang.reflect.*;
import java.sql.*;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tracks JDBC ownership without exposing physical driver handles. / 追踪 JDBC 所有权，不暴露物理驱动句柄。 */
public final class ManagedJdbcConnection {
    /** Physical connection whose confirmed close releases occupancy. / 确认关闭后才释放占用的物理连接。 */
    private final Connection physical;

    /** Idempotent host callback retried until release is confirmed after actual close. / 实际关闭后重试直到确认释放的幂等宿主回调。 */
    private final Release release;

    /** Thread allowed to use this connection and its descendants. / 允许使用连接及派生对象的线程。 */
    private final Thread owner = Thread.currentThread();

    /** Identity cache preserving JDBC parent relationships. / 保持 JDBC 父对象关系的身份缓存。 */
    private final Map<Object, Object> wrappers = new IdentityHashMap<>();

    /** Whether confirmed physical close has been reported. / 是否已报告确认的物理关闭。 */
    private final AtomicBoolean released = new AtomicBoolean();
    /**
     * Binds a physical connection to its opening thread. / 将物理连接绑定到打开它的线程。
     * @param physical owned physical connection / 所属物理连接
     * @param release callback after confirmed close / 确认关闭后的回调
     */
    private ManagedJdbcConnection(Connection physical, Release release) {
        this.physical = java.util.Objects.requireNonNull(physical);
        this.release = java.util.Objects.requireNonNull(release);
    }

    /**
     * Returns a standard JDBC view with tracked lifetime. / 返回跟踪生命周期的标准 JDBC 视图。
     * @param physical owned physical connection / 所属物理连接
     * @param release nonthrowing callback after confirmed close / 确认关闭后执行且不抛异常的回调
     * @return thread-bound connection / 绑定线程的连接
     */
    public static Connection wrap(Connection physical, Runnable release) {
        java.util.Objects.requireNonNull(release);
        return wrapTracked(physical, release::run);
    }

    /**
     * Tracks physical closure and retries failed host release persistence on the same handle. / 跟踪物理关闭，并在同一句柄上重试失败的宿主释放登记。
     * @param physical owned physical connection / 所属物理连接
     * @param release idempotent callback after confirmed physical closure / 确认物理关闭后的幂等回调
     * @return thread-bound connection preserving all JDBC parent handles / 保持全部 JDBC 父句柄关系的线程绑定连接
     */
    public static Connection wrapTracked(Connection physical, Release release) {
        return (Connection) new ManagedJdbcConnection(physical, release).managed(physical);
    }

    /** Idempotent host release after actual physical closure. / 实际物理关闭后的幂等宿主释放。 */
    @FunctionalInterface
    public interface Release {
        /**
         * Persists release; failures keep the close hook retryable. / 持久化释放；失败使关闭回调保持可重试。
         * @throws SQLException if host release could not be confirmed / 无法确认宿主释放时
         */
        void run() throws SQLException;
    }

    /**
     * Wraps every returned JDBC handle while retaining ordinary scalar values. / 包装返回的 JDBC 句柄，保留普通标量值。
     * @param value driver result, possibly null / 驱动返回值，可为空
     * @return managed handle or unchanged ordinary value / 受管句柄或原普通值
     */
    private Object managed(Object value) {
        if (value == null)
            return null;
        if (wrappers.containsKey(value))
            return wrappers.get(value);
        Class<?> type = value instanceof Connection
                ? Connection.class
                : value instanceof CallableStatement
                        ? CallableStatement.class
                        : value instanceof PreparedStatement
                                ? PreparedStatement.class
                                : value instanceof Statement
                                        ? Statement.class
                                        : value instanceof ResultSet
                                                ? ResultSet.class
                                                : value instanceof DatabaseMetaData
                                                        ? DatabaseMetaData.class
                                                        : value instanceof ResultSetMetaData
                                                                ? ResultSetMetaData.class
                                                                : value instanceof ParameterMetaData
                                                                        ? ParameterMetaData.class
                                                                        : value instanceof Blob
                                                                                ? Blob.class
                                                                                : value instanceof NClob
                                                                                        ? NClob.class
                                                                                        : value instanceof Clob
                                                                                                ? Clob.class
                                                                                                : value instanceof SQLXML
                                                                                                        ? SQLXML.class
                                                                                                        : value instanceof java.sql.Array
                                                                                                                ? java.sql.Array.class
                                                                                                                : value instanceof Struct
                                                                                                                        ? Struct.class
                                                                                                                        : value instanceof Ref
                                                                                                                                ? Ref.class
                                                                                                                                : null;
        if (type == null)
            return value;
        var proxy = Proxy.newProxyInstance(ManagedJdbcConnection.class.getClassLoader(), new Class<?>[]{type},
                (self, method, args) -> invoke(self, value, method, args));
        wrappers.put(value, proxy);
        return proxy;
    }

    /**
     * Enforces ownership and preserves uncertain failures instead of inventing release. / 执行所有权约束，保留不确定失败而不虚报释放。
     * @param self exposed proxy / 对外代理
     * @param target physical JDBC target / 物理 JDBC 目标
     * @param method requested interface method / 请求的接口方法
     * @param args method arguments, null for no arguments / 方法参数，无参时为空
     * @return wrapped method result / 包装后的方法结果
     * @throws Throwable if ownership, driver operation or confirmed close fails / 所有权、驱动操作或确认关闭失败时
     */
    private Object invoke(Object self, Object target, Method method, Object[] args) throws Throwable {
        var name = method.getName();
        if (method.getDeclaringClass() == Object.class)
            return switch (name) {
                case "equals" -> self == args[0];
                case "hashCode" -> System.identityHashCode(self);
                default -> "Managed JDBC " + self.getClass().getInterfaces()[0].getSimpleName();
            };
        if (Thread.currentThread() != owner)
            throw new SQLException("Connection used from another thread", "HY000");
        if (name.equals("isWrapperFor"))
            return ((Class<?>) args[0]).isInstance(self);
        if (name.equals("unwrap")) {
            if (((Class<?>) args[0]).isInstance(self))
                return self;
            throw new SQLException("Driver unwrap is unavailable", "0A000");
        }
        if (target instanceof DatabaseMetaData && name.equals("getURL"))
            throw new SQLException("Engine URL is private", "0A000");
        if (target == physical && name.equals("abort"))
            throw new SQLException("Forced interruption is unavailable", "0A000");
        if (target == physical && name.equals("close")) {
            if (released.get())
                return null;
            physical.close();
            if (!physical.isClosed())
                throw new SQLException("Connection is still open", "HY000");
            release.run();
            released.set(true);
            return null;
        }
        try {
            return managed(method.invoke(target, args));
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
