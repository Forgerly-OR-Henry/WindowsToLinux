package gold.debug.windowstolinux.api.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import gold.debug.windowstolinux.api.task.ApiException;
import gold.debug.windowstolinux.api.task.TaskContext;

public interface ModDatabase {
    SchemaIdentity identity() throws ApiException;
    Connection openConnection(TaskContext task) throws ApiException, SQLException;
}
