package gold.debug.windowstolinux.app.db.init;

import java.sql.Connection;
import java.util.UUID;

/** Pinned table transformation inside a private validated image. */
interface OfflineTableChange {
    String oldSha();
    String newSha();
    void rebuild(Connection connection, UUID generation, OfflineSchemaMaintenance.Hook hook) throws Exception;
}
