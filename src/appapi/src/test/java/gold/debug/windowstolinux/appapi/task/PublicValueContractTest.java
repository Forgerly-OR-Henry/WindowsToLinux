package gold.debug.windowstolinux.appapi.task;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.*;
import java.util.*;

import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.ai.model.AiRequest;
import gold.debug.windowstolinux.appapi.ai.model.AiUsage;
import gold.debug.windowstolinux.appapi.ai.vision.AiImage;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.file.source.FileContent;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.server.model.ServerInfo;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.process.ProcessSpec;
import gold.debug.windowstolinux.appapi.task.query.PageSlice;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.junit.jupiter.api.Test;

class PublicValueContractTest {
    Problem problem() {
        return new Problem(ProblemCode.UNKNOWN, "test.unknown", "unobserved", Map.of(), Optional.empty());
    }

    @Test
    void mutableInputsAndReturnedArraysCannotChangeAcceptedRequests() {
        var bytes = new byte[]{1, 2};
        var image = new AiImage("image/png", bytes);
        var file = new FileContent("test", bytes, "digest");
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
    void commandExecutionSnapshotSurvivesOriginalInputRelease() {
        byte[] original = {4, 5, 6};
        var request = new CommandRequest("cat", original, Duration.ofSeconds(2), 16);
        original[0] = 9;
        var stable = request.snapshot();
        request.close();
        request.close();
        assertArrayEquals(new byte[]{0, 0, 0}, request.stdin());
        assertArrayEquals(new byte[]{4, 5, 6}, stable.stdin());
        stable.close();
        assertArrayEquals(new byte[]{0, 0, 0}, stable.stdin());
    }

    @Test
    void uncertaintyCannotBeMisrepresentedAsSuccessOrAReasonlessFailure() {
        assertThrows(IllegalArgumentException.class,
            () -> new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
            () -> new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
            () -> new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.of(problem())));
        var unknown = new OperationResult<>(TaskResult.UNKNOWN, Optional.of("observed evidence"), Optional.of(problem()));
        assertEquals(TaskResult.UNKNOWN, unknown.outcome());
        assertTrue(unknown.value().isPresent());
        assertEquals(TaskResult.CANCELLED,
            new OperationResult<>(TaskResult.CANCELLED, Optional.empty(), Optional.empty()).outcome());
    }

    @Test
    void taskTerminalFieldsCannotDisagreeAndDuplicateResourcesAreRejected() {
        var now = Instant.now();
        assertThrows(IllegalArgumentException.class, () -> new TaskSnapshot(UUID.randomUUID(), "mod", "1.0.0", "test",
            TaskState.UNKNOWN, Optional.of(TaskResult.FAILED), "", now, Optional.of(now), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TaskSnapshot(UUID.randomUUID(), "mod", "1.0.0", "test",
            TaskState.ENDED, Optional.empty(), "", now, Optional.of(now), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TaskSnapshot(UUID.randomUUID(), "mod", "1.0.0", "test",
            TaskState.ENDED, Optional.of(TaskResult.UNKNOWN), "", now, Optional.of(now), List.of()));
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
        assertThrows(IllegalArgumentException.class, () -> new ServerInfo(new ServerId("id"), "name", "host", 65536,
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
