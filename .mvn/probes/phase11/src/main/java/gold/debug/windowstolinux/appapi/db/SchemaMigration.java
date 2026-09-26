package gold.debug.windowstolinux.appapi.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.TaskContext;

public interface SchemaMigration {
    void migrate(SchemaAccess source, SchemaAccess target) throws Exception;
}
