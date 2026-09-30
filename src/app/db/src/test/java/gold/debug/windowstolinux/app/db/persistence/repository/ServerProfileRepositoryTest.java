package gold.debug.windowstolinux.app.db.persistence.repository;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.TaskState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerProfileRepositoryTest {
    @TempDir
    Path directory;

    private final StoredServerProfile old = new StoredServerProfile("first", "example.test", 22, "tester",
            "ssh/first/password");

    @Test
    void preservesNamesChecksAndStableIdentityAcrossReopen() throws Exception {
        try (var db = PlatformRepositoryTestFixture.open(directory)) {
            PlatformRepositoryTestFixture.credential(db, old.credentialKey());
            db.servers().saveServerProfile(old);
        }
        StoredServerProfile renamed = new StoredServerProfile(old.id(), old.host(), 22, old.username(),
                old.credentialKey(), "Production EU");
        try (var db = PlatformRepositoryTestFixture.open(directory)) {
            assertEquals(old, db.servers().findServerProfile("first").orElseThrow());
            assertTrue(db.servers().observation("first").checkedAt().isEmpty());
            db.servers().recordObservation(old, true, "Ubuntu / amd64");
            db.servers().saveServerProfile(renamed);
        }
        try (var db = PlatformRepositoryTestFixture.open(directory)) {
            assertEquals(renamed, db.servers().listServerProfiles().getFirst());
            assertTrue(db.servers().observation("first").connected());
            assertTrue(db.servers().observation("first").checkedAt().isPresent());
            StoredServerProfile changed = new StoredServerProfile(old.id(), "other.test", 2222, old.username(),
                    old.credentialKey(), renamed.displayName());
            db.servers().saveServerProfile(changed);
            db.servers().recordObservation(old, false, "");
            assertTrue(db.servers().observation("first").checkedAt().isEmpty(),
                    "late check must not attach to edited endpoint");
            db.servers().recordObservation(changed, false, "");
            assertFalse(db.servers().observation("first").connected());
            assertTrue(db.servers().observation("first").checkedAt().isPresent());
        }
    }

    /** Keeps editable display names while original queued/running/unknown work prevents endpoint changes or deletion. / 保留可编辑名称，同时由原排队、运行及未知任务禁止端点变化或删除。 */
    @Test
    void acceptedOriginalTaskProtectsDraftEndpointAndDeleteUntilExplicitReconciliation() throws Exception {
        UUID original;
        var renamed = new StoredServerProfile(old.id(), old.host(), old.sshPort(), old.username(), old.credentialKey(),
                "Working draft");
        var changed = new StoredServerProfile(old.id(), "other.test", 2222, old.username(), old.credentialKey(),
                renamed.displayName());
        try (var db = PlatformRepositoryTestFixture.open(directory)) {
            PlatformRepositoryTestFixture.credential(db, old.credentialKey());
            db.servers().saveServerProfile(old);
            assertThrows(SQLException.class, () -> db.servers().saveServerProfile(new StoredServerProfile("missing",
                    old.host(), old.sshPort(), old.username(), "missing/credential")));
            assertTrue(db.servers().findServerProfile("missing").isEmpty());
            var ledger = new TaskLedger(db.database());
            original = PlatformRepositoryTestFixture.queue(db, "@platform", "platform.ssh.check.v1", old.id());
            assertEquals(TaskState.QUEUED, ledger.queries().snapshot(original).state());
            db.servers().saveServerProfile(renamed);
            assertEquals(renamed, db.servers().findServerProfile(old.id()).orElseThrow());
            assertThrows(SQLException.class, () -> db.servers().saveServerProfile(changed));
            assertThrows(SQLException.class, () -> db.servers().deleteServerProfile(old.id()));
            assertEquals(renamed, db.servers().findServerProfile(old.id()).orElseThrow());
            assertTrue(ledger.tryStart(original));
            assertTrue(ledger.queries().holds(original, new TaskResource("SERVER", old.id(), "@platform", true)));
            assertThrows(SQLException.class, () -> db.servers().saveServerProfile(changed));
            assertThrows(SQLException.class, () -> db.servers().deleteServerProfile(old.id()));
        }
        try (var db = PlatformRepositoryTestFixture.open(directory)) {
            var ledger = new TaskLedger(db.database());
            var retained = ledger.queries().snapshot(original);
            assertEquals(original, retained.id());
            assertEquals("@platform", retained.ownerId());
            assertEquals("1.0.0", retained.ownerVersion());
            assertEquals(TaskState.UNKNOWN, retained.state());
            assertTrue(retained.outcome().isEmpty());
            assertTrue(ledger.queries().holds(original, new TaskResource("SERVER", old.id(), "@platform", true)));
            assertThrows(SQLException.class, () -> ledger.tryStart(original));
            assertThrows(SQLException.class, () -> db.servers().saveServerProfile(changed));
            assertThrows(SQLException.class, () -> db.servers().deleteServerProfile(old.id()));
            assertEquals(renamed, db.servers().findServerProfile(old.id()).orElseThrow());
            assertEquals(TaskState.ENDED, ledger.completeAndArchive(original, Outcome.CANCELLED, "{}", true, () -> {
            }).state());
            assertEquals(Optional.of(old.credentialKey()), db.servers().deleteServerProfile(old.id()));
            assertTrue(db.servers().findServerProfile(old.id()).isEmpty());
            assertTrue(db.security().credentialIds().contains(old.credentialKey()),
                    "Repository deletion returns the credential identity without deleting separately owned credentials");
            assertEquals(Optional.empty(), db.servers().deleteServerProfile(old.id()));
        }
    }
}
