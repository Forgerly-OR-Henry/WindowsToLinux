package gold.debug.windowstolinux.app.ui.mod;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;

import gold.debug.windowstolinux.app.service.mod.PlatformModIntents.Choice;
import org.junit.jupiter.api.Test;

class ModSelectionDraftTest {
    private static final Choice SAVED = new Choice(false, Optional.of("1.0.0"));

    private static final Choice EDITED = new Choice(true, Optional.of("2.0.0"));

    @Test
    void sameOwnerRefreshRetainsUnsavedVersionAndSwitchWhileCleanRefreshUsesSavedFacts() {
        var draft = new ModSelectionDraft();
        draft.bind("one", SAVED);
        draft.edit(EDITED);
        long session = draft.session();
        draft.bind("one", new Choice(false, Optional.of("1.1.0")));
        assertEquals(EDITED, draft.choice());
        assertTrue(draft.changed());
        assertEquals(session, draft.session());
        draft.accepted("one", session, EDITED);
        assertFalse(draft.changed());
        draft.bind("one", SAVED);
        assertEquals(SAVED, draft.choice());
    }

    @Test
    void changingOrCollapsingOwnerDiscardsDraftAndReturningDoesNotRestoreIt() {
        var draft = new ModSelectionDraft();
        draft.bind("one", SAVED);
        draft.edit(EDITED);
        draft.bind("two", SAVED);
        assertEquals(SAVED, draft.choice());
        draft.bind("one", SAVED);
        assertEquals(SAVED, draft.choice());
        draft.edit(EDITED);
        draft.bind(null, null);
        assertNull(draft.choice());
        assertFalse(draft.changed());
        draft.bind("one", SAVED);
        assertEquals(SAVED, draft.choice());
    }

    @Test
    void oldSaveCompletionCannotOverwriteAnotherOwnerOrAReopenedSession() {
        var draft = new ModSelectionDraft();
        draft.bind("one", SAVED);
        long original = draft.session();
        draft.edit(EDITED);
        draft.bind("two", SAVED);
        draft.accepted("one", original, EDITED);
        assertEquals(SAVED, draft.choice());
        draft.bind("one", SAVED);
        assertFalse(draft.matches("one", original));
        draft.accepted("one", original, EDITED);
        assertEquals(SAVED, draft.choice());
    }

    @Test
    void repeatedEmptyRefreshKeepsExplicitEmptySelectionAndDoesNotCreateAnEditor() {
        var draft = new ModSelectionDraft();
        long empty = draft.session();
        draft.bind(null, null);
        draft.bind(null, null);
        assertTrue(draft.matches(null, empty));
        assertFalse(draft.changed());
        draft.edit(EDITED);
        assertNull(draft.choice());
    }
}
