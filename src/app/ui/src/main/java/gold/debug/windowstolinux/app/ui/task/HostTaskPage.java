package gold.debug.windowstolinux.app.ui.task;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import gold.debug.windowstolinux.app.service.task.PlatformTaskManagement;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.appapi.ui.page.ModPage;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.appapi.ui.page.UiNavigationRequest;

/** Host-rendered task controls retaining exact activation and view lifetimes. */
public final class HostTaskPage implements ModPage {
    private final PlatformTaskManagement management;
    private final String owner, version;
    private final BooleanSupplier activation;
    private final DesktopPartFactory components;
    private final PageMessagePresenter messages;
    private TaskRecoveryPane pane;
    private volatile boolean closed;
    private boolean locked = true;
    private UiEnvironment renderedEnvironment;

    public HostTaskPage(PlatformTaskManagement management, String owner, String version, BooleanSupplier activation,
                        DesktopPartFactory components, PageMessagePresenter messages) {
        this.management = management;
        this.owner = owner;
        this.version = version;
        this.activation = activation;
        this.components = components;
        this.messages = messages;
    }

    @Override public JComponent create(UiEnvironment environment) {
        requireEdt();
        if (closed || pane != null || !activation.getAsBoolean()) throw new IllegalStateException("Task view unavailable");
        pane = new TaskRecoveryPane(management.scoped(owner, version, () -> !closed && activation.getAsBoolean()),
            components, messages, null);
        pane.setName("mod.tasks");
        environmentChanged(environment);
        return pane;
    }

    @Override public void environmentChanged(UiEnvironment environment) {
        requireEdt();
        locked = environment.locked();
        if (!closed && pane != null && !environment.equals(renderedEnvironment)) {
            pane.localize();
            pane.setLocked(locked || !activation.getAsBoolean());
            renderedEnvironment = environment;
        }
    }

    @Override public void navigate(UiNavigationRequest<?> request) {
        requireEdt();
        if (closed || locked || pane == null || !activation.getAsBoolean()) throw new IllegalStateException("Task view unavailable");
        if (!request.destination().equals("task") || request.contextType() != UUID.class)
            throw new IllegalArgumentException("Original task navigation required");
        if (request.context().isPresent()) pane.selectTask((UUID) request.context().orElseThrow());
        else pane.refresh();
    }

    @Override public void close() {
        requireEdt();
        closed = true;
        if (pane != null) pane.close();
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Task page requires EDT");
    }
}
