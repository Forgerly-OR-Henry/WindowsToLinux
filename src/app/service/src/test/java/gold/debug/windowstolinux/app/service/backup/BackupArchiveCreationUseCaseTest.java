package gold.debug.windowstolinux.app.service.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import gold.debug.windowstolinux.shared.backup.contract.validation.BackupArchivePolicy;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupArchiveValidator;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.format.BackupArchiveContent;
import gold.debug.windowstolinux.shared.backup.manifest.BackupComponent;
import gold.debug.windowstolinux.shared.backup.manifest.BackupComponentRuntime;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabase;
import gold.debug.windowstolinux.shared.backup.manifest.BackupHealthCheck;
import gold.debug.windowstolinux.shared.backup.manifest.BackupIdentity;
import gold.debug.windowstolinux.shared.backup.manifest.BackupInventory;
import gold.debug.windowstolinux.shared.backup.manifest.BackupManifest;
import gold.debug.windowstolinux.shared.backup.manifest.BackupMember;
import gold.debug.windowstolinux.shared.backup.manifest.BackupMemberKind;
import gold.debug.windowstolinux.shared.backup.manifest.BackupRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupArchiveCreationUseCaseTest {
    @TempDir
    Path temporary;

    @Test
    void independentlyValidatesAndAtomicallyPublishesOneCompleteArchive() throws Exception {
        ArchiveFixture fixture = fixture();
        Path destination = temporary.resolve("sample.wtl-backup.zip");

        CreatedBackupArchive created = new BackupArchiveCreationUseCase().create(fixture.manifest(), fixture.contents(),
                destination);

        assertEquals(destination.toAbsolutePath(), created.archive());
        assertEquals("sample", created.inspection().applicationId());
        assertTrue(Files.isRegularFile(destination));
        assertEquals(created.inspection().archiveSha256(),
                new BackupArchiveValidator(BackupArchivePolicy.defaults()).validate(destination).archiveSha256());
        try (var files = Files.list(temporary)) {
            assertEquals(List.of(destination), files.toList());
        }

        assertThrows(IOException.class,
                () -> new BackupArchiveCreationUseCase().create(fixture.manifest(), fixture.contents(), destination));
        assertEquals(created.inspection().archiveSha256(),
                new BackupArchiveValidator(BackupArchivePolicy.defaults()).validate(destination).archiveSha256());
    }

    @Test
    void streamMismatchLeavesNeitherDestinationNorTemporaryPublication() throws Exception {
        ArchiveFixture fixture = fixture();
        BackupArchiveContent first = fixture.contents().get(0);
        List<BackupArchiveContent> mismatched = new java.util.ArrayList<>(fixture.contents());
        mismatched.set(0, new BackupArchiveContent(first.member(),
                () -> new ByteArrayInputStream("wrong".getBytes(StandardCharsets.UTF_8))));
        Path destination = temporary.resolve("failed.wtl-backup.zip");

        assertThrows(BackupException.class, () -> new BackupArchiveCreationUseCase().create(fixture.manifest(),
                List.copyOf(mismatched), destination));

        assertFalse(Files.exists(destination));
        try (var files = Files.list(temporary)) {
            assertTrue(files.toList().isEmpty());
        }
    }

    private static ArchiveFixture fixture() throws Exception {
        Map<String, byte[]> values = new LinkedHashMap<>();
        values.put("releases/sample.json", "release".getBytes(StandardCharsets.UTF_8));
        values.put("config/sample.json", "configuration".getBytes(StandardCharsets.UTF_8));
        values.put("runtime/sample.service", "service-definition".getBytes(StandardCharsets.UTF_8));
        List<BackupMember> members = values.entrySet().stream().map(entry -> new BackupMember(entry.getKey(),
                entry.getValue().length, digest(entry.getValue()), kind(entry.getKey()))).toList();
        BackupHealthCheck health = BackupHealthCheck.tcp(8080, 30, 5);
        BackupComponent component = new BackupComponent("sample", "sample", "a".repeat(64), "releases/sample.json",
                "config/sample.json", "runtime/sample.service", List.of(),
                new BackupComponentRuntime.NodeService(22, health), "b".repeat(64), List.of());
        BackupInventory inventory = new BackupInventory(List.of("releases/sample.json"), List.of("config/sample.json"),
                List.of(), List.of(), List.of(), BackupDatabase.none(),
                new BackupIdentity("sample", "server-1", "/opt/windowstolinux/apps/sample",
                        BackupInventory.computeReleaseSetSha256(List.of(component))),
                List.of("runtime/sample.service"), List.of(component), "sample", health,
                new BackupRuntime("ubuntu", "24.04", "systemd", "255", "x86_64", List.of("systemd")), List.of());
        BackupManifest manifest = BackupManifest.create(Instant.parse("2026-08-22T00:00:00Z"), "sample", inventory,
                members);
        List<BackupArchiveContent> contents = members.stream().map(
                member -> new BackupArchiveContent(member, () -> new ByteArrayInputStream(values.get(member.path()))))
                .toList();
        return new ArchiveFixture(manifest, contents);
    }

    private static String digest(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static BackupMemberKind kind(String path) {
        if (path.startsWith("releases/"))
            return BackupMemberKind.RELEASE;
        if (path.startsWith("config/"))
            return BackupMemberKind.CONFIGURATION;
        return BackupMemberKind.RUNTIME;
    }

    private record ArchiveFixture(BackupManifest manifest, List<BackupArchiveContent> contents) {
    }
}
