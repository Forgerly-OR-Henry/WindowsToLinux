package gold.debug.windowstolinux.app.ui.component;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.i18n.*;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
class AdvancedWindowTransitionTest {
    @Test void maximumAndRestoreRetainNormalWorkspaceBoundsAndOneInspector() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        org.junit.jupiter.api.Assumptions.assumeTrue(Toolkit.getDefaultToolkit().isFrameStateSupported(Frame.MAXIMIZED_BOTH));
        AtomicReference<JFrame> frame = new AtomicReference<>(); AtomicReference<AdvancedWindowHost> host = new AtomicReference<>(); AtomicReference<AdvancedOptionsPane> page = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            frame.set(new JFrame()); JPanel root = new JPanel(new BorderLayout());
            page.set(new AdvancedOptionsPane(new JPanel(),new DesktopComponentFactory(ThemePalette.light()),new PageMessagePresenter(MessageCatalog.forLanguageTag("en"))));
            page.get().field("field.sshPort",new JTextField("2222")); root.add(page.get()); frame.get().setContentPane(root); frame.get().setBounds(20,20,520,360);
            host.set(new AdvancedWindowHost(frame.get(),root)); host.get().activate(page.get()); frame.get().setVisible(true); page.get().setExpanded(true); assertEquals(840,frame.get().getWidth());
        });
        try {
            SwingUtilities.invokeAndWait(() -> frame.get().setExtendedState(Frame.MAXIMIZED_BOTH)); awaitState(frame.get(),true);
            SwingUtilities.invokeAndWait(() -> { assertEquals(520,host.get().workspaceBounds().width,"appearance rebuild must retain normal restore width"); assertEquals(1,java.util.Arrays.stream(frame.get().getOwnedWindows()).filter(Window::isShowing).count()); });
            SwingUtilities.invokeAndWait(() -> frame.get().setExtendedState(Frame.NORMAL)); awaitState(frame.get(),false);
            SwingUtilities.invokeAndWait(() -> { assertEquals(520,host.get().workspaceBounds().width); page.get().setExpanded(false); assertEquals(520,frame.get().getWidth()); assertEquals("2222",((JTextField)findInput(page.get().drawer())).getText()); });
        } finally { SwingUtilities.invokeAndWait(() -> { host.get().close(); frame.get().dispose(); }); }
    }
    private static Component findInput(Container root) { for(Component child:root.getComponents()) { if(child instanceof JTextField)return child; if(child instanceof Container nested){Component found=findInput(nested);if(found!=null)return found;} } return null; }
    private static void awaitState(JFrame frame,boolean maximum) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean[] reached={false};
        while(!reached[0] && System.nanoTime()<deadline) { SwingUtilities.invokeAndWait(() -> reached[0]=((frame.getExtendedState()&Frame.MAXIMIZED_BOTH)!=0)==maximum); Thread.sleep(50); }
        assertTrue(reached[0]); Thread.sleep(250); SwingUtilities.invokeAndWait(() -> {});
    }
}
