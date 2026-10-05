package gold.debug.windowstolinux.mod.git.file;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import gold.debug.windowstolinux.mod.git.api.GitReferenceKind;
import gold.debug.windowstolinux.mod.git.api.GitSourceRequest;
import org.junit.jupiter.api.Test;

class GitSourceContractTest {
    private GitSourceRequest request(String remote, GitReferenceKind kind, String ref, Set<String> hosts, boolean local) {
        return new GitSourceRequest(UUID.randomUUID(), UUID.randomUUID(), remote, kind, ref, hosts, local,
            "E:\\Program\\Git\\cmd\\git.exe");
    }

    @Test
    void exactReferenceKindsAndHostPoliciesRoundTripWithoutArchiveOwnership() throws Exception {
        for (var kind : GitReferenceKind.values()) {
            String value = switch (kind) {
                case DEFAULT_BRANCH -> "HEAD";
                case BRANCH -> "feature/source";
                case TAG -> "v1.0";
                case COMMIT -> "0123456789abcdef0123456789abcdef01234567";
            };
            var input = request("https://example.invalid/repo.git", kind, value, Set.of("example.invalid"), false);
            var checked = GitMessages.request(input);
            var encoded = GitMessages.encode(checked);
            assertFalse(encoded.contains("maximumArchiveBytes"));
            var decoded = GitMessages.decode(encoded);
            assertEquals(input, GitMessages.source(input.operationId(), input.materialId(), input.gitExecutable(), decoded));
        }
    }

    @Test
    void untrustedTransportAndChangedReferenceIdentityAreRejectedBeforeWork() {
        assertThrows(IllegalArgumentException.class, () -> GitMessages.request(request("https://user:secret@example.invalid/r",
            GitReferenceKind.BRANCH, "main", Set.of("example.invalid"), false)));
        assertThrows(IllegalArgumentException.class, () -> GitMessages.request(request("https://example.invalid/r?token=secret",
            GitReferenceKind.BRANCH, "main", Set.of("example.invalid"), false)));
        assertThrows(IllegalArgumentException.class, () -> GitMessages.request(request("https://other.invalid/r",
            GitReferenceKind.BRANCH, "main", Set.of("example.invalid"), false)));
        assertThrows(IllegalArgumentException.class, () -> GitMessages.request(request("file:///C:/repo",
            GitReferenceKind.BRANCH, "main", Set.of(), false)));
        assertThrows(IllegalArgumentException.class, () -> GitMessages.request(request("ssh://example.invalid/r",
            GitReferenceKind.BRANCH, "main", Set.of("example.invalid"), false)));
        assertThrows(IllegalArgumentException.class, () -> GitMessages.request(request("https://example.invalid/r",
            GitReferenceKind.DEFAULT_BRANCH, "main", Set.of("example.invalid"), false)));
        assertThrows(IllegalArgumentException.class, () -> GitMessages.request(request("https://example.invalid/r",
            GitReferenceKind.COMMIT, "deadbeef", Set.of("example.invalid"), false)));
        assertThrows(IllegalArgumentException.class, () -> GitMessages.request(request("https://example.invalid/r",
            GitReferenceKind.BRANCH, "main..other", Set.of("example.invalid"), false)));
    }

    @Test
    void treeRejectsLinksSubmodulesDuplicateControlAndAmbiguousProtocolFields() throws Exception {
        String hash = "0123456789abcdef0123456789abcdef01234567";
        assertEquals(java.util.List.of("中文/source.txt", ".gitattributes"), GitCheckout.members(
            ("100644 blob " + hash + "\t中文/source.txt\0" + "100755 blob " + hash + "\t.gitattributes\0")
                .getBytes(StandardCharsets.UTF_8)));
        for (String bad : java.util.List.of("120000 blob " + hash + "\tlink\0", "160000 commit " + hash + "\tsub\0",
            "100644 blob " + hash + "\t../escape\0", "100644 blob " + hash + "\t.git/config\0",
            "100644 blob " + hash + "\t.gitmodules\0", "100644 blob " + hash + "\tname"))
            assertThrows(java.io.IOException.class, () -> GitCheckout.members(bad.getBytes(StandardCharsets.UTF_8)));
        var good = GitMessages.encode(GitMessages.request(request("https://example.invalid/r", GitReferenceKind.BRANCH,
            "main", Set.of("example.invalid"), false)));
        assertThrows(java.io.IOException.class, () -> GitMessages.decode(good.replace("\"permitLocalFileRemote\":false",
            "\"permitLocalFileRemote\":null")));
        assertThrows(java.io.IOException.class, () -> GitMessages.decode(good.replace("\"kind\":\"BRANCH\"",
            "\"kind\":\"BRANCH\",\"kind\":\"TAG\"")));
        assertThrows(java.io.IOException.class, () -> GitMessages.decode(good.substring(0, good.length() - 1) + ",\"unknown\":true}"));
    }
}
