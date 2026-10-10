package gold.debug.windowstolinux.app.service.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.*;

import gold.debug.windowstolinux.app.db.ai.StoredAiModelProfile;
import gold.debug.windowstolinux.appapi.ai.model.*;
import org.junit.jupiter.api.Test;

class NativeModelContinuationsTest {
    private final NativeModelContinuations states = new NativeModelContinuations();

    private final UUID task = UUID.randomUUID();

    private final StoredAiModelProfile model = new StoredAiModelProfile("model", "https://fixture.invalid/api", "fixture", "reference");

    private AiRequest request(AiPurpose purpose, AiContinuation handle) {
        return new AiRequest(purpose, List.of(new AiMessage(AiRole.ASSISTANT, "", List.of(), List.of(), Optional.empty(), Optional.of(handle))),
            128, Duration.ofSeconds(5), false, List.of());
    }

    @Test
    void continuationRejectsOtherTaskPurposeAndRevision() {
        var handle = states.retain(task, AiPurpose.LANGUAGE, model, "revision", "opaque");
        assertEquals(model, states.bound(task, request(AiPurpose.LANGUAGE, handle), "revision").orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> states.bound(UUID.randomUUID(), request(AiPurpose.LANGUAGE, handle), "revision"));
        assertThrows(IllegalArgumentException.class, () -> states.bound(task, request(AiPurpose.APPROVAL, handle), "revision"));
        assertThrows(IllegalArgumentException.class, () -> states.bound(task, request(AiPurpose.LANGUAGE, handle), "changed"));
    }

    @Test
    void successfulBindingSurvivesHistoryRemovalAndFailureSealsSession() {
        var fresh = new AiRequest(AiPurpose.LANGUAGE, List.of(new AiMessage(AiRole.USER, "next", List.of())), 128, Duration.ofSeconds(5), false);
        assertTrue(states.bound(task, fresh, "revision").isEmpty());
        states.stop(task, AiPurpose.LANGUAGE);
        assertTrue(states.bound(task, fresh, "revision").isEmpty());
        states.retain(task, AiPurpose.LANGUAGE, model, "revision", "opaque");
        assertEquals(model, states.bound(task, fresh, "revision").orElseThrow());
        states.stop(task, AiPurpose.LANGUAGE);
        assertThrows(IllegalArgumentException.class, () -> states.bound(task, fresh, "revision"));
        states.clear(task);
        assertTrue(states.bound(task, fresh, "revision").isEmpty());
    }

    @Test
    void providerPayloadNeverAppearsInPublicHandle() {
        var handle = states.retain(task, AiPurpose.LANGUAGE, model, "revision", "private-signature-content");
        assertFalse(handle.toString().contains("private-signature-content"));
        assertEquals("private-signature-content", states.values(task, request(AiPurpose.LANGUAGE, handle), model, "revision").get(handle.id()));
        assertThrows(IllegalArgumentException.class, () -> states.values(task, request(AiPurpose.LANGUAGE, handle), new StoredAiModelProfile("other", "https://fixture.invalid/api", "fixture", "reference"), "revision"));
    }

    @Test
    void utf8MetadataIsHostMeasuredAndCannotBeForged() {
        var handle = states.retain(task, AiPurpose.LANGUAGE, model, "revision", "秘密状态");
        assertEquals(12, handle.payloadBytes());
        assertThrows(IllegalArgumentException.class, () -> states.bound(task,
            request(AiPurpose.LANGUAGE, new AiContinuation(handle.id(), 1)), "revision"));
        assertThrows(IllegalArgumentException.class, () -> states.retain(task, AiPurpose.LANGUAGE, model, "revision", "中".repeat(400000)));
    }
}
