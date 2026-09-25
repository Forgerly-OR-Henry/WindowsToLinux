package gold.debug.windowstolinux.api.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import gold.debug.windowstolinux.api.task.ApiException;
import gold.debug.windowstolinux.api.task.TaskContext;

public interface ModSchemaLifecycle {
    void initialize(SchemaAccess target) throws Exception;
    ValidationReport validate(SchemaAccess target) throws Exception;
}
