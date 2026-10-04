package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformRemoteCapabilitiesTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformServerRefTest.completed;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import gold.debug.windowstolinux.app.windows.file.WindowsLinks;
import gold.debug.windowstolinux.appapi.file.model.FileInfo;
import gold.debug.windowstolinux.appapi.file.model.FilePath;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import org.apache.sshd.common.file.root.RootedFileSystem;
import org.apache.sshd.sftp.server.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileLinkAssociationIntegrationTest {
    @TempDir
    Path root;

    @Test
    void genuineRuntimePersistsUnverifiedAssociationsForMissingAndWireInaccessibleLinkTargets() throws Exception {
        try (var remote = new Remote(root.resolve("ssh")); var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            runtime.setSourcePicker((a, b, c) -> root);
            runtime.setInteractionPresenter(new Reviews());
            Path inaccessible = Files.createDirectory(remote.files.resolve("inaccessible")), missing = Files.createDirectory(remote.files.resolve("missing"));
            Files.writeString(inaccessible.resolve("keep"), "private original");
            Path privateLink = remote.files.resolve("private-link"), missingLink = remote.files.resolve("missing-link");
            WindowsLinks.create(privateLink, inaccessible);
            WindowsLinks.create(missingLink, missing);
            Files.delete(missing);
            var subsystem = (SftpSubsystemFactory) remote.listener.server().getSubsystemFactories().getFirst();
            subsystem.setFileSystemAccessor(new SftpFileSystemAccessor() {
                Path local(Path path) {
                    var fs = (RootedFileSystem) path.getFileSystem();
                    return fs.getRoot().resolve(path.toAbsolutePath().toString().substring(1)).normalize();
                }

                @Override
                public NavigableMap<String, Object> resolveReportedFileAttributes(SftpSubsystemProxy s, Path path, int flags, NavigableMap<String, Object> attrs, LinkOption... options) throws IOException {
                    Path nativePath = local(path);
                    if (nativePath.equals(inaccessible)) throw new AccessDeniedException(path.toString());
                    if (nativePath.equals(privateLink) || nativePath.equals(missingLink)) {
                        attrs.put("isSymbolicLink", true);
                        attrs.put("isDirectory", false);
                        attrs.put("isRegularFile", false);
                    }
                    return attrs;
                }

                @Override
                public String resolveLinkTarget(SftpSubsystemProxy s, Path path) throws IOException {
                    Path nativePath = local(path);
                    if (nativePath.equals(privateLink)) return "/inaccessible";
                    if (nativePath.equals(missingLink)) return "/missing";
                    throw new IOException("Not an owned fixture link");
                }
            });
            try (var file = runtime.capabilities().source(OWNER, VERSION)) {
                completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), task -> {
                    for (String name : List.of("private-link", "missing-link")) {
                        var path = FilePath.remote(SERVER, "/" + name);
                        assertEquals(FileInfo.Kind.LINK, file.readLink(path, task).kind());
                        assertThrows(ApiException.class, () -> file.getFile(path, task));
                        file.registerLink(path, task);
                    }
                    assertEquals(2, count(runtime.persistence().database(), "SELECT COUNT(*) FROM file_link WHERE last_state='UNVERIFIED' AND target_path IS NULL AND target_identity IS NULL"));
                    assertEquals("private original", Files.readString(inaccessible.resolve("keep")));
                    return success(null);
                }));
            } finally {
                Files.deleteIfExists(privateLink);
                Files.deleteIfExists(missingLink);
            }
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }
}
