package probe;

import java.lang.reflect.*;
import java.sql.*;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TrackedJdbc {
    private final Connection physical;
    private final Runnable release;
    private final Thread owner = Thread.currentThread();
    private final Map<Object,Object> wrappers = new IdentityHashMap<>();
    private final AtomicBoolean released = new AtomicBoolean();
    private TrackedJdbc(Connection physical,Runnable release) { this.physical=physical;this.release=release; }
    public static Connection wrap(Connection physical,Runnable release) { return (Connection)new TrackedJdbc(physical,release).managed(physical); }
    private Object managed(Object value) {
        if(value==null)return null;
        if(wrappers.containsKey(value))return wrappers.get(value);
        Class<?> type = value instanceof Connection ? Connection.class : value instanceof CallableStatement ? CallableStatement.class : value instanceof PreparedStatement ? PreparedStatement.class : value instanceof Statement ? Statement.class : value instanceof ResultSet ? ResultSet.class : value instanceof DatabaseMetaData ? DatabaseMetaData.class : value instanceof Blob ? Blob.class : value instanceof NClob ? NClob.class : value instanceof Clob ? Clob.class : value instanceof SQLXML ? SQLXML.class : value instanceof java.sql.Array ? java.sql.Array.class : null;
        if(type==null)return value;
        var proxy=Proxy.newProxyInstance(TrackedJdbc.class.getClassLoader(),new Class<?>[]{type},(self,method,args)->invoke(self,value,method,args));
        wrappers.put(value,proxy);return proxy;
    }
    private Object invoke(Object self,Object target,Method method,Object[] args)throws Throwable {
        var name=method.getName();
        if(method.getDeclaringClass()==Object.class)return switch(name){case "equals" -> self==args[0];case "hashCode" -> System.identityHashCode(self);default -> "Managed JDBC "+self.getClass().getInterfaces()[0].getSimpleName();};
        if(Thread.currentThread()!=owner)throw new SQLException("Connection used from another thread","HY000");
        if(name.equals("isWrapperFor"))return ((Class<?>)args[0]).isInstance(self);
        if(name.equals("unwrap")) { if(((Class<?>)args[0]).isInstance(self))return self;throw new SQLException("Driver unwrap is unavailable","0A000"); }
        if(target instanceof DatabaseMetaData && name.equals("getURL"))throw new SQLException("Engine URL is private","0A000");
        if(target==physical && name.equals("abort"))throw new SQLException("Forced interruption is unavailable","0A000");
        if(target==physical && name.equals("close")) {
            if(released.get())return null;
            physical.close();
            if(!physical.isClosed())throw new SQLException("Connection is still open","HY000");
            if(released.compareAndSet(false,true))release.run();
            return null;
        }
        try { return managed(method.invoke(target,args)); }
        catch(InvocationTargetException failure) { throw failure.getCause(); }
    }
}
