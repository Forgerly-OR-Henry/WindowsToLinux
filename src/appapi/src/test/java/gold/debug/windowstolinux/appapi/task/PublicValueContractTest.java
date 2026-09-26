package gold.debug.windowstolinux.appapi.task;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.*;
import java.util.*;

import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.linux.CommandRequest;
import gold.debug.windowstolinux.appapi.server.ServerInfo;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.source.SourceFile;
import org.junit.jupiter.api.Test;

class PublicValueContractTest {
    Problem problem() {
        return new Problem(ProblemCode.UNKNOWN, "test.unknown", "unobserved", Map.of(), Optional.empty());
    }

    @Test
    void mutableInputsAndReturnedArraysCannotChangeAcceptedRequests() {
        var bytes = new byte[]{1, 2};
        var image = new AiImage("image/png", bytes);
        var file = new SourceFile("test", bytes, "digest");
        var command = new CommandRequest("echo", bytes, Duration.ofSeconds(1), 1024);
        bytes[0] = 9;
        assertEquals(1, image.content()[0]);
        assertEquals(1, file.content()[0]);
        assertEquals(1, command.stdin()[0]);
        image.content()[0] = 8;
        file.content()[0] = 8;
        command.stdin()[0] = 8;
        assertEquals(1, image.content()[0]);
        assertEquals(1, file.content()[0]);
        assertEquals(1, command.stdin()[0]);
        var servers = new ArrayList<>(List.of("one"));
        var task = new TaskRequest("read", "Read", servers, true);
        servers.add("two");
        assertEquals(List.of("one"), task.serverIds());
        assertThrows(UnsupportedOperationException.class, () -> task.serverIds().add("three"));
        var context = new HashMap<>(Map.of("stage", "read"));
        var issue = new Problem(ProblemCode.IO_FAILURE, "read.failed", "failed", context, Optional.empty());
        context.put("stage", "write");
        assertEquals("read", issue.context().get("stage"));
    }

    @Test
    void uncertaintyCannotBeMisrepresentedAsSuccessOrAReasonlessFailure() {
        assertThrows(IllegalArgumentException.class,
                () -> new OperationResult<>(Outcome.UNKNOWN, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new OperationResult<>(Outcome.FAILED, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.of(problem())));
        var unknown = new OperationResult<>(Outcome.UNKNOWN, Optional.of("observed evidence"), Optional.of(problem()));
        assertEquals(Outcome.UNKNOWN, unknown.outcome());
        assertTrue(unknown.value().isPresent());
        assertEquals(Outcome.CANCELLED,
                new OperationResult<>(Outcome.CANCELLED, Optional.empty(), Optional.empty()).outcome());
    }

    @Test
    void taskTerminalFieldsCannotDisagreeAndDuplicateResourcesAreRejected() {
        var now = Instant.now();
        assertThrows(IllegalArgumentException.class, () -> new TaskSnapshot(UUID.randomUUID(), "mod", "1.0.0", "test",
                TaskState.UNKNOWN, Optional.of(Outcome.FAILED), "", now, Optional.of(now), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TaskSnapshot(UUID.randomUUID(), "mod", "1.0.0", "test",
                TaskState.ENDED, Optional.empty(), "", now, Optional.of(now), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TaskSnapshot(UUID.randomUUID(), "mod", "1.0.0", "test",
                TaskState.ENDED, Optional.of(Outcome.UNKNOWN), "", now, Optional.of(now), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskRequest("test", "task", List.of("same", "same"), true));
    }

    @Test
    void budgetsSchemaIdentityAndProcessPathsHaveExplicitBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> new AiRequest(AiPurpose.LANGUAGE, List.of(), 0, Duration.ofSeconds(1), false));
        assertThrows(IllegalArgumentException.class, () -> new CommandRequest("", new byte[0], Duration.ZERO, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new CommandRequest("", new byte[0], Duration.ofSeconds(1), -1));
        assertThrows(IllegalArgumentException.class, () -> new AiUsage(Optional.of(-1L), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new SchemaIdentity("mod", "01", UUID.randomUUID()));
        assertDoesNotThrow(() -> new SchemaIdentity("mod", "9".repeat(64), UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessSpec(Path.of("relative"), List.of(), Map.of(), Path.of("."), Duration.ofSeconds(10)));
        assertThrows(IllegalArgumentException.class, () -> new ServerInfo(new ServerRef("id"), "name", "host", 65536,
                "user", Optional.empty(), Optional.empty()));
    }

    @Test
    void requiredValuesAndCollectionElementsCannotBeNull() {
        assertThrows(NullPointerException.class, () -> new ApprovalGrant(null));
        assertThrows(NullPointerException.class,
                () -> new TaskRequest("type", "title", Arrays.asList("server", null), true));
        assertThrows(NullPointerException.class, () -> new PageSlice<>(List.of(), null));
        assertThrows(NullPointerException.class, () -> new ApiException(null));
    }
}
