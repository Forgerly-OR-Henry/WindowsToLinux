package gold.debug.windowstolinux.app.db.server;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.app.db.connect.PlatformStoreTestFixture;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerProfileStoreTest {
    @TempDir
    Path directory;

    private final StoredServerProfile old = new StoredServerProfile("first", "example.test", 22, "tester",
        "ssh/first/password");

    @Test
    void reorderPersistsWithoutChangingProfilesTrustObservationsOrCredentialReferences() throws Exception {
        try (var db = PlatformStoreTestFixture.open(directory)) {
            PlatformStoreTestFixture.credential(db, old.credentialKey());
            db.servers().saveServerProfile(old);
            db.servers().saveServerProfile(new StoredServerProfile("a", "second.test", 22, "tester", old.credentialKey()));
            db.servers().saveServer(new StoredServerIdentity(old.id(), old.host(), old.sshPort(), "SHA256:fixture"));
            db.servers().recordObservation(old, true, "Ubuntu");
            var observation = db.servers().observation(old.id());
            var trust = db.servers().findServer(old.id());
            assertEquals(List.of("first", "a"), ids(db.servers()));
            db.servers().reorderServerProfiles(List.of("first", "a"), List.of("a", "first"));
            assertEquals(observation, db.servers().observation(old.id()));
            assertEquals(trust, db.servers().findServer(old.id()));
            assertEquals(old, db.servers().findServerProfile(old.id()).orElseThrow());
            assertEquals(List.of(old.credentialKey()), db.security().credentialIds());
        }
        try (var db = PlatformStoreTestFixture.open(directory)) {
            assertEquals(List.of("a", "first"), ids(db.servers()));
            db.servers().saveServerProfile(new StoredServerProfile(old.id(), old.host(), old.sshPort(), old.username(),
                old.credentialKey(), "renamed"));
            db.servers().saveServerProfile(new StoredServerProfile("b", "third.test", 22, "tester", old.credentialKey()));
            assertEquals(List.of("a", "first", "b"), ids(db.servers()));
            db.servers().deleteServerProfile("a");
            db.servers().saveServerProfile(new StoredServerProfile("c", "fourth.test", 22, "tester", old.credentialKey()));
            assertEquals(List.of("first", "b", "c"), ids(db.servers()));
        }
    }

    @Test
    void rejectsStaleIncompleteDuplicateAndForeignOrdersWithoutPartialWrites() throws Exception {
        try (var db = PlatformStoreTestFixture.open(directory)) {
            PlatformStoreTestFixture.credential(db, old.credentialKey());
            db.servers().saveServerProfile(old);
            db.servers().saveServerProfile(new StoredServerProfile("second", "second.test", 22, "tester", old.credentialKey()));
            var expected = List.of("first", "second");
            for (var requested : List.of(List.of("first"), List.of("second", "second"), List.of("second", "foreign"))) {
                assertThrows(SQLException.class, () -> db.servers().reorderServerProfiles(expected, requested));
                assertEquals(expected, ids(db.servers()));
            }
            db.servers().reorderServerProfiles(expected, List.of("second", "first"));
            assertThrows(SQLException.class, () -> db.servers().reorderServerProfiles(expected, expected));
            assertEquals(List.of("second", "first"), ids(db.servers()));
            db.servers().saveServerProfile(new StoredServerProfile("third", "third.test", 22, "tester", old.credentialKey()));
            assertThrows(SQLException.class,
                () -> db.servers().reorderServerProfiles(List.of("second", "first"), expected));
            assertEquals(List.of("second", "first", "third"), ids(db.servers()));
        }
    }

    @Test
    void sqlFailureAfterFirstTemporaryOrderWriteRollsBackRanksAndProfileAttributesAcrossReopen() throws Exception {
        var second = new StoredServerProfile("second", "second.test", 2222, "other", old.credentialKey(), "Second server");
        StoredServerObservation observation = null;
        Optional<StoredServerIdentity> trust = Optional.empty();
        for (int pass = 0; pass < 2; pass++) {
            try (var db = PlatformStoreTestFixture.open(directory)) {
                if (pass == 0) {
                    PlatformStoreTestFixture.credential(db, old.credentialKey());
                    db.servers().saveServerProfile(old);
                    db.servers().saveServerProfile(second);
                    db.servers().saveServer(new StoredServerIdentity(old.id(), old.host(), old.sshPort(), "SHA256:rollback"));
                    db.servers().recordObservation(old, true, "Ubuntu");
                    observation = db.servers().observation(old.id());
                    trust = db.servers().findServer(old.id());
                    try (var connection = db.database().openConnection(); var statement = connection.createStatement()) {
                        statement.execute("ALTER TABLE server_profile ADD CONSTRAINT fixture_server_order_mid_phase CHECK(display_order<>3)");
                        try {
                            var failure = assertThrows(SQLException.class,
                                () -> db.servers().reorderServerProfiles(List.of("first", "second"), List.of("second", "first")));
                            assertEquals("23513", failure.getSQLState(), "The second temporary rank must hit the real CHECK constraint");
                            assertTrue(failure.getMessage().toLowerCase(java.util.Locale.ROOT)
                                .contains("fixture_server_order_mid_phase"), failure::getMessage);
                        } finally {
                            statement.execute("ALTER TABLE server_profile DROP CONSTRAINT fixture_server_order_mid_phase");
                        }
                    }
                }
                try (var connection = db.database().openConnection(); var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT server_profile_id,display_order FROM server_profile ORDER BY server_profile_id")) {
                    assertTrue(rows.next());
                    assertEquals("first", rows.getString(1));
                    assertEquals(0, rows.getInt(2), "Rollback must restore the original numeric rank");
                    assertTrue(rows.next());
                    assertEquals("second", rows.getString(1));
                    assertEquals(1, rows.getInt(2), "The first temporary UPDATE must not survive the failed transaction");
                    assertFalse(rows.next());
                }
                assertEquals(List.of(old, second), db.servers().listServerProfiles());
                assertEquals(observation, db.servers().observation(old.id()));
                assertEquals(trust, db.servers().findServer(old.id()));
                assertEquals(List.of(old.credentialKey()), db.security().credentialIds());
            }
        }
    }

    private static List<String> ids(ServerProfileStore store) throws SQLException {
        return store.listServerProfiles().stream().map(StoredServerProfile::id).toList();
    }

    @Test
    void preservesNamesChecksAndStableIdentityAcrossReopen() throws Exception {
        try (var db = PlatformStoreTestFixture.open(directory)) {
            PlatformStoreTestFixture.credential(db, old.credentialKey());
            db.servers().saveServerProfile(old);
        }
        StoredServerProfile renamed = new StoredServerProfile(old.id(), old.host(), 22, old.username(),
            old.credentialKey(), "Production EU");
        try (var db = PlatformStoreTestFixture.open(directory)) {
            assertEquals(old, db.servers().findServerProfile("first").orElseThrow());
            assertTrue(db.servers().observation("first").checkedAt().isEmpty());
            db.servers().recordObservation(old, true, "Ubuntu / amd64");
            db.servers().saveServerProfile(renamed);
        }
        try (var db = PlatformStoreTestFixture.open(directory)) {
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
        try (var db = PlatformStoreTestFixture.open(directory)) {
            PlatformStoreTestFixture.credential(db, old.credentialKey());
            db.servers().saveServerProfile(old);
            assertThrows(SQLException.class, () -> db.servers().saveServerProfile(new StoredServerProfile("missing",
                old.host(), old.sshPort(), old.username(), "missing/credential")));
            assertTrue(db.servers().findServerProfile("missing").isEmpty());
            var ledger = new TaskLedger(db.database());
            original = PlatformStoreTestFixture.queue(db, "@platform", "platform.ssh.check.v1", old.id());
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
        try (var db = PlatformStoreTestFixture.open(directory)) {
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
            assertEquals(TaskState.ENDED, ledger.completeAndArchive(original, TaskResult.CANCELLED, "{}", true, () -> {
            }).state());
            assertEquals(Optional.of(old.credentialKey()), db.servers().deleteServerProfile(old.id()));
            assertTrue(db.servers().findServerProfile(old.id()).isEmpty());
            assertTrue(db.security().credentialIds().contains(old.credentialKey()),
                "Repository deletion returns the credential identity without deleting separately owned credentials");
            assertEquals(Optional.empty(), db.servers().deleteServerProfile(old.id()));
        }
    }
}
