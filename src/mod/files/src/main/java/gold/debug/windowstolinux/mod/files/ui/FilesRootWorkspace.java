package gold.debug.windowstolinux.mod.files.ui;

import java.util.UUID;
import javax.swing.*;
import gold.debug.windowstolinux.appapi.ui.UiService;
import gold.debug.windowstolinux.appapi.ui.page.ModPage;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.appapi.ui.page.UiNavigationRequest;
import gold.debug.windowstolinux.mod.files.service.FilesService;

/** Keeps file browsing and its exact-release task template inside one owned root. */
public final class FilesRootWorkspace implements ModPage {
    private final FilesWorkspace files;
    private final UiService ui;
    private final JTabbedPane tabs = new JTabbedPane();
    private final JPanel taskSlot = new JPanel(new java.awt.BorderLayout());
    private final JLabel status = new JLabel();
    private final JPanel root = new JPanel(new java.awt.BorderLayout());
    private ModPage tasks;
    private UiEnvironment environment;
    private FilesMessages messages;
    private boolean closed, filesClosed, created, selecting, selectTasks;

    public FilesRootWorkspace(FilesService service, UiService ui) {
        files = new FilesWorkspace(service);
        this.ui = ui;
    }

    @Override public JComponent create(UiEnvironment environment) throws Exception {
        requireEdt();
        if (closed || created) throw new IllegalStateException("Files root already used");
        this.environment = environment;
        messages = new FilesMessages(environment.locale());
        created = true;
        tabs.setName("files.workspace.tabs");
        tabs.addTab(messages.text("workspace.files"), files.create(environment));
        tabs.addTab(messages.text("workspace.tasks"), taskSlot);
        root.add(tabs, java.awt.BorderLayout.CENTER);
        root.add(status, java.awt.BorderLayout.SOUTH);
        tabs.addChangeListener(ignored -> {
            if (!closed && !selecting && tabs.getSelectedIndex() == 1) try {
                showTasks();
                status.setText("");
            } catch (Exception unavailable) {
                status.setText(messages.text("workspace.tasksUnavailable"));
            }
        });
        if (selectTasks) { tabs.setSelectedIndex(1); showTasks(); }
        return root;
    }

    private void showTasks() throws Exception {
        if (closed) throw new IllegalStateException("Task view unavailable");
        if (tasks == null) tasks = ui.createTaskPage();
        if (taskSlot.getComponentCount() == 0) {
            try { taskSlot.add(tasks.create(environment), java.awt.BorderLayout.CENTER); }
            catch (Exception unavailable) { tasks.close(); tasks = null; throw unavailable; }
            taskSlot.revalidate();
        }
    }

    @Override public String navigationId() {
        requireEdt();
        return (created ? tabs.getSelectedIndex() == 1 : selectTasks) ? "tasks" : "files";
    }

    @Override public void restoreNavigation(String id) {
        requireEdt();
        if (!id.equals("tasks") && !id.equals("files")) return;
        selectTasks = id.equals("tasks");
        if (created) tabs.setSelectedIndex(selectTasks ? 1 : 0);
    }

    @Override public void navigate(UiNavigationRequest<?> request) throws Exception {
        requireEdt();
        if (closed || environment.locked()) throw new IllegalStateException("Files root unavailable");
        if (request.destination().equals("tasks") && request.contextType() == UUID.class) {
            selecting = true;
            try { tabs.setSelectedIndex(1); showTasks(); }
            finally { selecting = false; }
            tasks.navigate(new UiNavigationRequest<>("task", UUID.class, request.context().map(UUID.class::cast)));
        } else {
            tabs.setSelectedIndex(0);
            files.navigate(request);
        }
    }

    @Override public void environmentChanged(UiEnvironment environment) {
        requireEdt();
        this.environment = environment;
        if (!created || closed) return;
        messages.update(environment.locale());
        tabs.setTitleAt(0, messages.text("workspace.files"));
        tabs.setTitleAt(1, messages.text("workspace.tasks"));
        files.environmentChanged(environment);
        if (tasks != null) tasks.environmentChanged(environment);
    }

    @Override public void close() throws Exception {
        requireEdt();
        closed = true;
        if (tasks != null) { tasks.close(); tasks = null; taskSlot.removeAll(); }
        if (!filesClosed) { files.close(); filesClosed = true; }
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Files root requires EDT");
    }
}
