package gold.debug.windowstolinux.shared.linux.protocol.restore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteRestoreStagingRequestTest {
    @TempDir
    Path temporary;

    @Test
    void bindsCandidateNamespaceAndExactMemberBytes() throws Exception {
        String digest = "a".repeat(64);
        Path root = Files.createDirectory(temporary.resolve("sample-" + digest.substring(0, 16)));

        RemoteRestoreStagingRequest request = new RemoteRestoreStagingRequest("sample", digest, root, 3,
                List.of(new RemoteRestoreMember("config/application.json", 3, "b".repeat(64))));

        assertEquals("sample-aaaaaaaaaaaaaaaa", request.candidateId());
    }

    @Test
    void rejectsTraversalCaseCollisionsByteMismatchAndUnboundRoot() throws Exception {
        String digest = "a".repeat(64);
        Path root = Files.createDirectory(temporary.resolve("sample-" + digest.substring(0, 16)));
        RemoteRestoreMember lower = new RemoteRestoreMember("config/application.json", 3, "b".repeat(64));
        RemoteRestoreMember upper = new RemoteRestoreMember("CONFIG/application.json", 3, "c".repeat(64));

        assertThrows(IllegalArgumentException.class, () -> new RemoteRestoreMember("../escape", 1, "b".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> new RemoteRestoreStagingRequest("sample", digest, root, 6, List.of(lower, upper)));
        assertThrows(IllegalArgumentException.class,
                () -> new RemoteRestoreStagingRequest("sample", digest, root, 4, List.of(lower)));
        assertThrows(IllegalArgumentException.class,
                () -> new RemoteRestoreStagingRequest("sample", digest, temporary, 3, List.of(lower)));
    }
}
