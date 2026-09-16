package gold.debug.windowstolinux.app.db.persistence.repository;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.db.entity.*;
import gold.debug.windowstolinux.shared.model.lifecycle.*;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExternalApplicationRepositoryTest {
    @TempDir Path directory;
    private final String firstId = "external:" + UUID.randomUUID();
    private final Instant time = Instant.parse("2026-09-16T00:00:00Z");
    private StoredExternalApplication registration(String id, String fingerprint) {
        return new StoredExternalApplication(id, "server", "example.test", 22, "tester",
                new DiscoveredApplication(new ExternalApplicationTarget(ExternalApplicationKind.SYSTEMD, "web.service", fingerprint), "Web", RuntimeState.STOPPED, true, true, false), time, time);
    }

    @Test void deduplicatesPersistsAndRequiresExplicitReattachmentAfterIdentityChange() throws Exception {
        try (var db = DesktopPersistence.open(directory)) {
            db.servers().saveServerProfile(new StoredServerProfile("server", "example.test", 22, "tester", "ssh/server/password", "MASTER_PASSWORD"));
            assertEquals(firstId, db.externalApplications().adopt(registration(firstId, "a".repeat(64))));
            assertEquals(firstId, db.externalApplications().adopt(registration("external:" + UUID.randomUUID(), "a".repeat(64))));
            db.externalApplications().savePresentation(new StoredApplicationPresentation(firstId, "Website", "WEBSITE", Optional.of(new UserAccessUrl(URI.create("https://example.test")))));
        }
        try (var db = DesktopPersistence.open(directory)) {
            assertEquals(1, db.externalApplications().list().size()); assertEquals(time, db.externalApplications().list().getFirst().adoptedAt());
            assertEquals("WEBSITE", db.externalApplications().presentation(firstId).orElseThrow().category());
            var replacement = registration("external:" + UUID.randomUUID(), "b".repeat(64));
            assertThrows(java.sql.SQLException.class, () -> db.externalApplications().adopt(replacement));
            assertTrue(db.externalApplications().find(firstId).isPresent());
            assertEquals(replacement.id(), db.externalApplications().adopt(replacement, true));
            assertTrue(db.externalApplications().find(firstId).isEmpty()); assertTrue(db.externalApplications().presentation(firstId).isEmpty());
            assertEquals(1, db.externalApplications().list().size()); assertTrue(db.managedApplications().list().isEmpty());
        }
    }
}
