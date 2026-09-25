package gold.debug.windowstolinux.shared.backup.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import org.junit.jupiter.api.Test;

class BackupManifestSchemaV4Test {
    private static final BackupHealthCheck HEALTH = BackupHealthCheck.tcp(8080, 30, 5);

    @Test
    void bindsDependencyOrderedReleasesAndExactSecretUnion() {
        SecretReference firstRevision = new SecretReference("shared-token", 1);
        SecretReference secondRevision = new SecretReference("shared-token", 2);
        BackupComponent database = component("database", List.of(), "a".repeat(64), List.of(firstRevision));
        BackupComponent api = component("api", List.of("database"), "b".repeat(64),
                List.of(firstRevision, secondRevision));
        List<BackupComponent> components = List.of(database, api);
        String releaseSet = BackupInventory.computeReleaseSetSha256(components);

        BackupInventory inventory = inventory(components, releaseSet, List.of(firstRevision, secondRevision));

        assertEquals(releaseSet, inventory.identity().releaseSetSha256().orElseThrow());
        assertEquals(List.of(firstRevision, secondRevision), inventory.secretReferences());
    }

    @Test
    void rejectsWrongReleaseSetAndIncompleteApplicationSecretUnion() {
        SecretReference secret = new SecretReference("database-password", 3);
        BackupComponent component = component("database", List.of(), "a".repeat(64), List.of(secret));
        List<BackupComponent> components = List.of(component);

        assertThrows(IllegalArgumentException.class, () -> inventory(components, "f".repeat(64), List.of(secret)));
        assertThrows(IllegalArgumentException.class,
                () -> inventory(components, BackupInventory.computeReleaseSetSha256(components), List.of()));
    }

    @Test
    void digestChangesWhenOrderIdentityOrReleaseChanges() {
        BackupComponent first = component("first", List.of(), "a".repeat(64), List.of());
        BackupComponent second = component("second", List.of("first"), "b".repeat(64), List.of());
        BackupComponent changedRelease = component("second", List.of("first"), "c".repeat(64), List.of());
        BackupComponent changedIdentity = component("third", List.of("first"), "b".repeat(64), List.of());

        String baseline = BackupInventory.computeReleaseSetSha256(List.of(first, second));

        assertNotEquals(baseline, BackupInventory.computeReleaseSetSha256(List.of(second, first)));
        assertNotEquals(baseline, BackupInventory.computeReleaseSetSha256(List.of(first, changedRelease)));
        assertNotEquals(baseline, BackupInventory.computeReleaseSetSha256(List.of(first, changedIdentity)));
    }

    @Test
    void rejectsNonCanonicalOrDuplicateExactSecretReferences() {
        SecretReference first = new SecretReference("shared-token", 1);
        SecretReference second = new SecretReference("shared-token", 2);

        assertThrows(IllegalArgumentException.class,
                () -> component("api", List.of(), "a".repeat(64), List.of(second, first)));
        assertThrows(IllegalArgumentException.class,
                () -> component("api", List.of(), "a".repeat(64), List.of(first, first)));
    }

    @Test
    void secretExcludedV4RemainsInspectableButCannotActivateAutomatically() {
        SecretReference secret = new SecretReference("database-password", 3);
        BackupComponent component = component("database", List.of(), "a".repeat(64), List.of(secret));
        BackupInventory inventory = inventory(List.of(component),
                BackupInventory.computeReleaseSetSha256(List.of(component)), List.of(secret));
        List<BackupMember> publicMembers = members("database");
        BackupManifest excluded = BackupManifest.create(Instant.parse("2026-08-22T00:00:00Z"), "sample", inventory,
                publicMembers);
        BackupManifest included = BackupManifest.create(Instant.parse("2026-08-22T00:00:00Z"), "sample", inventory,
                java.util.stream.Stream
                        .concat(publicMembers.stream(), java.util.stream.Stream.of(
                                new BackupMember("secrets.enc", 1, "e".repeat(64), BackupMemberKind.ENCRYPTED_SECRETS)))
                        .toList());

        assertFalse(excluded.supportsAutomaticActivation());
        assertTrue(included.supportsAutomaticActivation());
    }

    @Test
    void rejectsEncryptedSecretMemberWithoutDeclaredReferences() {
        BackupComponent component = component("api", List.of(), "a".repeat(64), List.of());
        BackupInventory inventory = inventory(List.of(component),
                BackupInventory.computeReleaseSetSha256(List.of(component)), List.of());
        List<BackupMember> members = java.util.stream.Stream
                .concat(members("api").stream(),
                        java.util.stream.Stream.of(
                                new BackupMember("secrets.enc", 1, "e".repeat(64), BackupMemberKind.ENCRYPTED_SECRETS)))
                .toList();

        assertThrows(IllegalArgumentException.class,
                () -> BackupManifest.create(Instant.parse("2026-08-22T00:00:00Z"), "sample", inventory, members));
    }

    private static BackupInventory inventory(List<BackupComponent> components, String releaseSet,
            List<SecretReference> secrets) {
        List<String> ids = components.stream().map(BackupComponent::componentId).toList();
        return new BackupInventory(ids.stream().map(id -> "releases/" + id + ".json").toList(),
                ids.stream().map(id -> "config/" + id + ".json").toList(), secrets, List.of(), List.of(),
                BackupDatabase.none(),
                new BackupIdentity("sample", "server-1", "/opt/windowstolinux/apps/sample", releaseSet),
                ids.stream().map(id -> "runtime/" + id + ".service").toList(), components,
                components.getLast().componentId(), HEALTH,
                new BackupRuntime("ubuntu", "24.04", "systemd", "255", "x86_64", List.of("systemd")), List.of());
    }

    private static BackupComponent component(String id, List<String> dependencies, String releaseSha256,
            List<SecretReference> secrets) {
        return new BackupComponent(id, id, "d".repeat(64), "releases/" + id + ".json", "config/" + id + ".json",
                "runtime/" + id + ".service", dependencies, new BackupComponentRuntime.SpringBoot(HEALTH),
                releaseSha256, secrets);
    }

    private static List<BackupMember> members(String id) {
        return List.of(new BackupMember("releases/" + id + ".json", 1, "e".repeat(64), BackupMemberKind.RELEASE),
                new BackupMember("config/" + id + ".json", 1, "e".repeat(64), BackupMemberKind.CONFIGURATION),
                new BackupMember("runtime/" + id + ".service", 1, "e".repeat(64), BackupMemberKind.RUNTIME));
    }
}
