package probe;

import java.sql.DriverManager;

public final class DatabaseCrashFixture {
    public static void main(String[] args)throws Exception {
        try(var c=DriverManager.getConnection(args[0],"app_internal","probe-only");var s=c.createStatement()) {
            if(args[1].equals("open")){System.exit(7);return;}
            s.execute("CREATE TABLE app.operation(id INT PRIMARY KEY, state VARCHAR)");
            s.execute("INSERT INTO app.operation VALUES(1,'PREPARING')");
            s.execute("CREATE TABLE a.original(id INT PRIMARY KEY)");s.execute("INSERT INTO a.original VALUES(17)");
            s.execute("CREATE TABLE b.migrated(id INT PRIMARY KEY)");
            if(args[1].equals("complete"))s.execute("INSERT INTO b.migrated SELECT id FROM a.original");
            Runtime.getRuntime().halt(23);
        }catch(java.sql.SQLException failure){System.err.println(failure.getSQLState());System.exit(9);}
    }
}
