package gold.debug.windowstolinux.app.ui.server;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;

import gold.debug.windowstolinux.appapi.ui.extension.UiActionPresentation;
import org.junit.jupiter.api.Test;

class ServerExtensionMenuTest {
    @Test
    void unchangedPresentationLeavesMenuLayoutAndControlsUntouchedWhileChangesStayLive() throws Exception {
        var task = new FutureTask<Void>(() -> {
            var delete = new JButton("Delete");
            var recheck = new JButton("Recheck identity");
            var adapter = new ServerExtensionMenu(new JButton("More"), delete, recheck);
            var state = new UiActionPresentation("Open files", true, true);
            var action = (JMenuItem) adapter.createAction(state, () -> {
            });
            adapter.setContents(List.of(action));
            var field = ServerExtensionMenu.class.getDeclaredField("menu");
            field.setAccessible(true);
            var menu = (JPopupMenu) field.get(adapter);
            new JPanel().add(menu);
            var structure = new AtomicInteger();
            menu.addContainerListener(new ContainerAdapter() {
                @Override
                public void componentAdded(ContainerEvent event) {
                    structure.incrementAndGet();
                }

                @Override
                public void componentRemoved(ContainerEvent event) {
                    structure.incrementAndGet();
                }
            });
            var invalidations = new AtomicInteger();
            var repaints = new AtomicInteger();
            var previous = RepaintManager.currentManager(menu);
            RepaintManager.setCurrentManager(new RepaintManager() {
                @Override
                public synchronized void addInvalidComponent(JComponent component) {
                    if (component == menu || SwingUtilities.isDescendingFrom(component, menu))
                        invalidations.incrementAndGet();
                    super.addInvalidComponent(component);
                }

                @Override
                public void addDirtyRegion(JComponent component, int x, int y, int width, int height) {
                    if (component == menu || SwingUtilities.isDescendingFrom(component, menu))
                        repaints.incrementAndGet();
                    super.addDirtyRegion(component, x, y, width, height);
                }
            });
            try {
                adapter.updateAction(action, state);
                adapter.setContents(List.of(action));
                adapter.refresh();
                assertEquals(0, structure.get());
                assertEquals(0, invalidations.get());
                assertEquals(0, repaints.get());
                assertSame(action, menu.getComponent(0));

                adapter.updateAction(action, new UiActionPresentation("Choose files", true, false));
                delete.setText("Remove server");
                delete.setEnabled(false);
                recheck.setEnabled(false);
                adapter.refresh();
                assertEquals("Choose files", action.getText());
                assertEquals("Choose files", action.getAccessibleContext().getAccessibleName());
                assertFalse(action.isEnabled());
                var recheckItem = (JMenuItem) menu.getComponent(2);
                var deleteItem = (JMenuItem) menu.getComponent(3);
                assertFalse(recheckItem.isEnabled());
                assertEquals("Remove server", deleteItem.getText());
                assertFalse(deleteItem.isEnabled());
                assertEquals(0, structure.get());

                invalidations.set(0);
                repaints.set(0);
                adapter.setContents(List.of(action));
                adapter.refresh();
                assertEquals(0, invalidations.get());
                assertEquals(0, repaints.get());
                adapter.setContents(List.of());
                assertEquals(2, menu.getComponentCount());
                assertSame(recheckItem, menu.getComponent(0));
                assertSame(deleteItem, menu.getComponent(1));
                assertTrue(structure.get() > 0);
            } finally {
                RepaintManager.setCurrentManager(previous);
            }
            return null;
        });
        SwingUtilities.invokeAndWait(task);
        task.get();
    }
}
