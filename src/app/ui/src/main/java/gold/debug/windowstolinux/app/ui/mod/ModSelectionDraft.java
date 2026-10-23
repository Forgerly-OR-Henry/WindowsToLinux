package gold.debug.windowstolinux.app.ui.mod;

import java.util.Objects;

import gold.debug.windowstolinux.app.service.mod.PlatformModIntents.Choice;

/** One visible editor, discarded when its owner changes. / 仅属于当前可见对象、切换时丢弃的编辑草稿。 */
final class ModSelectionDraft {
    private String owner;

    private Choice saved, choice;

    private long session;

    void bind(String nextOwner, Choice nextSaved) {
        if (!Objects.equals(owner, nextOwner)) {
            owner = nextOwner;
            session++;
            saved = choice = nextSaved;
        } else {
            boolean keep = changed();
            saved = nextSaved;
            if (!keep) choice = nextSaved;
        }
    }

    void edit(Choice value) {
        if (owner != null) choice = Objects.requireNonNull(value);
    }

    Choice choice() {
        return choice;
    }

    boolean changed() {
        return owner != null && !Objects.equals(saved, choice);
    }

    long session() {
        return session;
    }

    boolean matches(String expectedOwner, long expectedSession) {
        return Objects.equals(owner, expectedOwner) && session == expectedSession;
    }

    void accepted(String expectedOwner, long expectedSession, Choice accepted) {
        if (matches(expectedOwner, expectedSession)) saved = choice = accepted;
    }
}
