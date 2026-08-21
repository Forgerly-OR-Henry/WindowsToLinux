package gold.debug.windowstolinux.shared.linux.sshd.backup.generation.script;

import gold.debug.windowstolinux.shared.linux.protocol.database.RemoteDatabasePort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseCommandRendererTest {
    @Test
    void rendersOnlyFixedHelperVerbAndOpaqueSecretReference() {
        DatabaseCommandRenderer renderer = new DatabaseCommandRenderer();
        RemoteDatabasePort.BackupRequest request = new RemoteDatabasePort.BackupRequest("sample",
                new RemoteDatabasePort.ConnectionProfile.Server(RemoteDatabasePort.DatabaseType.POSTGRESQL,
                        "db.example.test", 5432, "sample", "sample_user", "db-password", 7, true), false, false);

        String inspect = renderer.inspect(request);
        String export = renderer.export(request, RemoteDatabasePort.DatabaseConsistencyMode.POSTGRESQL_LOGICAL_DUMP);

        assertTrue(inspect.contains("'database-inspect' 'sample' 'postgresql'"));
        assertTrue(export.contains("'database-export' 'sample' 'postgresql-logical'"));
        assertTrue(export.contains("'db-password' '7' '1'"));
        assertFalse(export.contains("password="));
        assertFalse(export.contains("private-value"));
    }

    @Test
    void bindsTransferAndRestoreToExactArtifactEvidence() {
        DatabaseCommandRenderer renderer = new DatabaseCommandRenderer();
        RemoteDatabasePort.BackupArtifact artifact = new RemoteDatabasePort.BackupArtifact(
                "db-0123456789abcdef0123456789abcdef", 128, "a".repeat(64),
                RemoteDatabasePort.DatabaseType.SQLITE, "data/application.db", "3.46", "3.46",
                RemoteDatabasePort.DatabaseConsistencyMode.SQLITE_ONLINE_BACKUP, List.of(), List.of("verified"));
        RemoteDatabasePort.RestoreRequest restore = new RemoteDatabasePort.RestoreRequest(
                "sample", "sample-0123456789abcdef",
                new RemoteDatabasePort.ConnectionProfile.Sqlite("data/application.db"), artifact);

        assertTrue(renderer.readArtifact(artifact).contains("'128' '" + "a".repeat(64) + "'"));
        assertTrue(renderer.stageArtifact(artifact).contains("'database-stage-artifact'"));
        assertTrue(renderer.restore(restore).contains("'sample-0123456789abcdef' 'db-0123456789abcdef0123456789abcdef'"));
        assertTrue(renderer.discardCandidate(restore).contains("'database-discard-candidate'"));
    }
}
