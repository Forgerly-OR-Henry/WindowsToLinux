package gold.debug.windowstolinux.app.service.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.*;

import gold.debug.windowstolinux.app.db.ai.StoredAiModelProfile;
import gold.debug.windowstolinux.appapi.ai.model.*;
import org.junit.jupiter.api.Test;

class NativeModelContinuationsTest {
    private final NativeModelContinuations states = new NativeModelContinuations(
        gold.debug.windowstolinux.app.config.AppConfig.defaultsConfig().ai().nativeState());

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

    @Test
    void configuredCapacityKeepsEarlyMixedPurposeAndIndependentSamePurposeBranches() throws Exception {
        var configured = gold.debug.windowstolinux.app.config.ConfigLoader.load("app").ai().nativeState();
        var retained = new NativeModelContinuations(configured);
        var firstLanguage = retained.retain(task, AiPurpose.LANGUAGE, model, "revision", "first-language");
        var firstApproval = retained.retain(task, AiPurpose.APPROVAL, model, "revision", "first-approval");
        var otherBranch = retained.retain(task, AiPurpose.LANGUAGE, model, "revision", "independent-branch");
        for (int index = 3; index < 512; index++)
            retained.retain(task, index % 2 == 0 ? AiPurpose.LANGUAGE : AiPurpose.APPROVAL, model, "revision", "state-" + index);
        assertEquals(4096, configured.maxStates());
        assertEquals(64L * 1024 * 1024, configured.maxTotalBytes());
        assertEquals("first-language", retained.values(task, request(AiPurpose.LANGUAGE, firstLanguage), model, "revision").get(firstLanguage.id()));
        assertEquals("first-approval", retained.values(task, request(AiPurpose.APPROVAL, firstApproval), model, "revision").get(firstApproval.id()));
        assertEquals("independent-branch", retained.values(task, request(AiPurpose.LANGUAGE, otherBranch), model, "revision").get(otherBranch.id()));
    }

    @Test
    void configuredSmallCountIsTaskWideAndClearCannotRemoveAnotherTasksState() {
        var retained = new NativeModelContinuations(new gold.debug.windowstolinux.app.config.AppConfig.NativeState(2, 64, 32));
        var other = UUID.randomUUID();
        var otherHandle = retained.retain(other, AiPurpose.LANGUAGE, model, "revision", "other");
        var first = retained.retain(task, AiPurpose.LANGUAGE, model, "revision", "first");
        retained.retain(task, AiPurpose.APPROVAL, model, "revision", "second");
        assertThrows(IllegalArgumentException.class, () -> retained.retain(task, AiPurpose.LANGUAGE, model, "revision", "third"));
        assertEquals("first", retained.values(task, request(AiPurpose.LANGUAGE, first), model, "revision").get(first.id()));
        retained.stop(task, AiPurpose.LANGUAGE);
        retained.clear(task);
        assertThrows(IllegalArgumentException.class, () -> retained.values(task, request(AiPurpose.LANGUAGE, first), model, "revision"));
        assertEquals("other", retained.values(other, request(AiPurpose.LANGUAGE, otherHandle), model, "revision").get(otherHandle.id()));
        retained.retain(task, AiPurpose.LANGUAGE, model, "revision", "new");
        retained.retain(task, AiPurpose.APPROVAL, model, "revision", "new-second");
    }

    @Test
    void configuredUtf8AggregateAndIndividualBoundsRejectWithoutEvictingExistingHandles() {
        var retained = new NativeModelContinuations(new gold.debug.windowstolinux.app.config.AppConfig.NativeState(10, 12, 8));
        var first = retained.retain(task, AiPurpose.LANGUAGE, model, "revision", "中中");
        retained.retain(task, AiPurpose.APPROVAL, model, "revision", "abcdef");
        assertEquals(6, first.payloadBytes());
        assertThrows(IllegalArgumentException.class, () -> retained.retain(task, AiPurpose.LANGUAGE, model, "revision", "x"));
        assertThrows(IllegalArgumentException.class, () -> retained.retain(UUID.randomUUID(), AiPurpose.LANGUAGE, model, "revision", "123456789"));
        assertEquals("中中", retained.values(task, request(AiPurpose.LANGUAGE, first), model, "revision").get(first.id()));
    }

    @Test
    void firstRetentionFailureSealsOnlyTheOriginalPurposeUntilItsTaskIsCleared() {
        var retained = new NativeModelContinuations(new gold.debug.windowstolinux.app.config.AppConfig.NativeState(2, 16, 4));
        var fresh = new AiRequest(AiPurpose.LANGUAGE, List.of(new AiMessage(AiRole.USER, "next", List.of())), 128, Duration.ofSeconds(5), false);
        assertThrows(IllegalArgumentException.class, () -> retained.retain(task, AiPurpose.LANGUAGE, model, "revision", "too-large"));
        retained.stopAfterRetentionFailure(task, AiPurpose.LANGUAGE);
        assertThrows(IllegalArgumentException.class, () -> retained.bound(task, fresh, "revision"));
        var approval = retained.retain(task, AiPurpose.APPROVAL, model, "revision", "ok");
        assertEquals(model, retained.bound(task, request(AiPurpose.APPROVAL, approval), "revision").orElseThrow());
        retained.clear(task);
        assertTrue(retained.bound(task, fresh, "revision").isEmpty());
    }
}
