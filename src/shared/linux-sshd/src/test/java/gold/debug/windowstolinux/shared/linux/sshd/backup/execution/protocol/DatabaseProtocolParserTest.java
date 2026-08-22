package gold.debug.windowstolinux.shared.linux.sshd.backup.execution.protocol;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.protocol.database.RemoteDatabasePort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseProtocolParserTest {
    private final DatabaseProtocolParser parser = new DatabaseProtocolParser();

    @Test
    void parsesBoundedCompatibilityAndArtifactEvidence() throws Exception {
        var compatibility = parser.compatibility("""
                TYPE=postgresql
                ENGINE_VERSION=16.4
                TOOL_VERSION=16.4
                TOOL_AVAILABLE=1
                ENGINE_COMPATIBLE=1
                ONLINE_BACKUP_AVAILABLE=0
                ALL_TABLES_TRANSACTIONAL=1
                """);
        var artifact = parser.artifact("""
                TYPE=postgresql
                ENGINE_VERSION=16.4
                TOOL_VERSION=16.4
                CONSISTENCY_MODE=postgresql-logical
                ARTIFACT_ID=db-0123456789abcdef0123456789abcdef
                BYTE_COUNT=4096
                SHA256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
                LIMITED_NON_TRANSACTIONAL=0
                """, "db.example.test:5432/sample");

        assertEquals(RemoteDatabasePort.DatabaseType.POSTGRESQL, compatibility.type());
        assertTrue(compatibility.engineVersionCompatible());
        assertEquals(RemoteDatabasePort.DatabaseConsistencyMode.POSTGRESQL_LOGICAL_DUMP,
                artifact.consistencyMode());
        assertEquals(4096, artifact.byteCount());
    }

    @Test
    void parsesCompleteCommitAndRecoveryEvidence() throws Exception {
        var committed = parser.commit("""
                CANDIDATE_ID=sample-0123456789abcdef
                COMMITTED=1
                PREVIOUS_RETAINED=1
                """);
        var recovered = parser.recovery("""
                CANDIDATE_ID=sample-0123456789abcdef
                RECOVERED=1
                PREVIOUS_VERIFIED=1
                CANDIDATE_REMOVED=1
                """);

        assertTrue(committed.committed());
        assertTrue(committed.previousDatabaseRetained());
        assertTrue(recovered.recovered());
        assertTrue(recovered.previousDatabaseVerified());
        assertTrue(recovered.candidateRemoved());
    }

    @Test
    void rejectsIncompleteOrUnknownCommitAndRecoveryEvidence() {
        LinuxOperationException incomplete = assertThrows(LinuxOperationException.class, () -> parser.commit("""
                CANDIDATE_ID=sample-0123456789abcdef
                COMMITTED=1
                """));
        LinuxOperationException unknown = assertThrows(LinuxOperationException.class, () -> parser.recovery("""
                CANDIDATE_ID=sample-0123456789abcdef
                RECOVERED=1
                PREVIOUS_VERIFIED=1
                CANDIDATE_REMOVED=1
                EXTRA=unexpected
                """));

        assertEquals(LinuxOperationFailureType.DATABASE_EVIDENCE_INVALID.code(), incomplete.failure().code());
        assertEquals(LinuxOperationFailureType.DATABASE_EVIDENCE_INVALID.code(), unknown.failure().code());
    }

    @Test
    void rejectsMissingOrNonBooleanProtocolEvidence() {
        LinuxOperationException missing = assertThrows(LinuxOperationException.class,
                () -> parser.compatibility("TYPE=sqlite\n"));
        LinuxOperationException invalid = assertThrows(LinuxOperationException.class, () -> parser.restore("""
                CANDIDATE_ID=sample-0123456789abcdef
                CONNECTION_TOKEN=sample-0123456789abcdef
                INTEGRITY_VERIFIED=yes
                SCHEMA_READABLE=1
                """));

        assertEquals(LinuxOperationFailureType.DATABASE_EVIDENCE_INVALID.code(), missing.failure().code());
        assertEquals(LinuxOperationFailureType.DATABASE_EVIDENCE_INVALID.code(), invalid.failure().code());
    }

    @Test
    void rejectsDuplicateAndUnknownProtocolFields() {
        LinuxOperationException duplicate = assertThrows(LinuxOperationException.class,
                () -> parser.restore("""
                        CANDIDATE_ID=sample-0123456789abcdef
                        CANDIDATE_ID=sample-0123456789abcdef
                        CONNECTION_TOKEN=sample-0123456789abcdef
                        INTEGRITY_VERIFIED=1
                        SCHEMA_READABLE=1
                        """));
        LinuxOperationException unknown = assertThrows(LinuxOperationException.class,
                () -> parser.restore("""
                        CANDIDATE_ID=sample-0123456789abcdef
                        CONNECTION_TOKEN=sample-0123456789abcdef
                        INTEGRITY_VERIFIED=1
                        SCHEMA_READABLE=1
                        EXTRA=unexpected
                        """));

        assertEquals(LinuxOperationFailureType.DATABASE_EVIDENCE_INVALID.code(), duplicate.failure().code());
        assertEquals(LinuxOperationFailureType.DATABASE_EVIDENCE_INVALID.code(), unknown.failure().code());
    }
}
