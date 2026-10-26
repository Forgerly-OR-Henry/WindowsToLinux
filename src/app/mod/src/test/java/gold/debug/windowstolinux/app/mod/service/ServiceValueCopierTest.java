package gold.debug.windowstolinux.app.mod.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

class ServiceValueCopierTest {
    public record Snapshot(List<String> values) {
    }

    public record BinarySnapshot(byte[] runtime) {
    }

    @Test
    void copiesOptionalAiRequestAndEffectiveResponseBudgetsAcrossThePublicBoundary() throws Exception {
        var messages = List.of(new gold.debug.windowstolinux.appapi.ai.model.AiMessage(
            gold.debug.windowstolinux.appapi.ai.model.AiRole.USER, "hello", List.of()));
        for (Optional<Integer> tokens : List.of(Optional.<Integer>empty(), Optional.of(65536))) {
            var request = new gold.debug.windowstolinux.appapi.ai.model.AiRequest(
                gold.debug.windowstolinux.appapi.ai.model.AiPurpose.LANGUAGE, messages, tokens, java.time.Duration.ofSeconds(10), false);
            var copied = (gold.debug.windowstolinux.appapi.ai.model.AiRequest) ServiceValueCopier.copy(request,
                type -> type.getPackageName().startsWith("gold.debug.windowstolinux.appapi.ai"));
            assertEquals(request, copied);
            assertNotSame(request, copied);
        }
        var response = new gold.debug.windowstolinux.appapi.ai.model.AiResponse("ok", "model", "stop",
            new gold.debug.windowstolinux.appapi.ai.model.AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
            List.of(), Optional.empty(), Optional.of(128), Optional.of(32768));
        var copied = (gold.debug.windowstolinux.appapi.ai.model.AiResponse) ServiceValueCopier.copy(response,
            type -> type.getPackageName().startsWith("gold.debug.windowstolinux.appapi.ai"));
        assertEquals(response, copied);
        assertEquals(Optional.of(32768), copied.effectiveMaxOutputTokens());
    }

    @Test
    void copiesNestedRecordsAndCollectionsBeforeOriginalMutation() throws Exception {
        var values = new ArrayList<>(List.of("before"));
        var original = new Snapshot(values);
        var result = (Snapshot) ServiceValueCopier.copy(original, type -> type == Snapshot.class);
        values.add("after");
        assertEquals(List.of("before"), result.values());
        assertThrows(UnsupportedOperationException.class, () -> result.values().add("changed"));
        assertNotSame(original, result);
    }

    @Test
    void rejectsLiveObjectsPrivateValuesCyclesAndExcessiveGraphs() {
        var cycle = new ArrayList<>();
        cycle.add(cycle);
        for (var value : List.of(CompletableFuture.completedFuture("done"), new Object(), cycle,
            Collections.nCopies(4097, "x"), "x".repeat(1024 * 1024 + 1)))
            assertThrows(IllegalArgumentException.class, () -> ServiceValueCopier.copy(value, type -> true));
        assertThrows(IllegalArgumentException.class,
            () -> ServiceValueCopier.copy(new Snapshot(List.of()), type -> false));
    }

    @Test
    void copiesOneFullRuntimePayloadAndBoundsAggregateBinaryBytes() throws Exception {
        byte[] original = new byte[16 * 1024 * 1024];
        original[0] = 7;
        var copied = (BinarySnapshot) ServiceValueCopier.copy(new BinarySnapshot(original),
            type -> type == BinarySnapshot.class);
        original[0] = 3;
        assertEquals(7, copied.runtime()[0]);
        assertNotSame(original, copied.runtime());
        var returned = (BinarySnapshot) ServiceValueCopier.copy(copied, type -> type == BinarySnapshot.class);
        copied.runtime()[0] = 5;
        assertEquals(7, returned.runtime()[0]);
        assertNotSame(copied.runtime(), returned.runtime());
        byte[] shared = new byte[8 * 1024 * 1024 + 1];
        assertThrows(IllegalArgumentException.class,
            () -> ServiceValueCopier.copy(List.of(new BinarySnapshot(shared), new BinarySnapshot(shared)),
                type -> type == BinarySnapshot.class));
        assertThrows(IllegalArgumentException.class, () -> ServiceValueCopier.copy(new char[1], type -> true));
    }
}
