package gold.debug.windowstolinux.app.ui.ai;

import com.formdev.flatlaf.FlatLightLaf;
import gold.debug.windowstolinux.app.service.contract.AiApplicationFacade;
import gold.debug.windowstolinux.app.service.ai.*;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.i18n.*;
import gold.debug.windowstolinux.app.ui.deployment.ReviewContext;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class AiModelInventoryTest {
    private final DesktopComponentFactory c = new DesktopComponentFactory(ThemePalette.light());
    private final PageMessagePresenter messages = new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN"));
    private AiProviderSummary model(String id, boolean enabled, int priority) {
        return new AiProviderSummary(new AiProviderProfile(id, URI.create("https://example.test/v1/chat/completions"), "model-"+id, "ai/"+id, CredentialStorageMode.MASTER_PASSWORD), id, enabled, priority, priority == 0 ? Optional.empty() : Optional.of(Instant.now()));
    }
    @Test void rendersModelsAndMovesWithKeyboardAndDragSemantics() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        assertEquals(List.of("b","c","a"), AiProviderDragTransfer.move(List.of("a","b","c"),"a",3));
        assertEquals(List.of("c","a","b"), AiProviderDragTransfer.move(List.of("a","b","c"),"c",0));
        var values = new AtomicReference<>(List.of(model("first",false,0),model("second",true,1),model("third",true,2)));
        AiApplicationFacade service = (AiApplicationFacade) Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{AiApplicationFacade.class},(proxy,method,args) -> {
            if (method.getName().equals("listAiConfigurations")) return values.get();
            if (method.getName().equals("reorderAiProviders")) { List<?> ids = (List<?>) args[0]; var prior = values.get(); values.set(ids.stream().map(id -> prior.stream().filter(v -> v.profile().id().equals(id)).findFirst().orElseThrow()).toList()); return null; }
            throw new AssertionError(method);
        });
        ReviewContext context = (ReviewContext) Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ReviewContext.class},(p,m,a) -> Optional.empty());
        AtomicReference<JFrame> frame = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { FlatLightLaf.setup(); var page = new AiPage(service,context,c,messages); frame.set(new JFrame()); frame.get().setContentPane(page.panel()); frame.get().setSize(1050,750); frame.get().setVisible(true); });
        try {
            idle();
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(descendants(frame.get()).filter(JLabel.class::isInstance).map(JLabel.class::cast).anyMatch(label -> label.getText().equals("优先使用")));
                var card = descendants(frame.get()).filter(JPanel.class::isInstance).map(JPanel.class::cast).filter(panel -> panel.getActionMap().get("alt DOWN") != null).findFirst().orElseThrow();
                card.getActionMap().get("alt DOWN").actionPerformed(new ActionEvent(card,0,"alt DOWN"));
            });
            idle(); assertEquals(List.of("second","first","third"),values.get().stream().map(value -> value.profile().id()).toList());
            SwingUtilities.invokeAndWait(() -> {
                RepaintManager.currentManager(frame.get()).validateInvalidComponents(); frame.get().validate();
                var name = descendants(frame.get()).filter(JLabel.class::isInstance).map(JLabel.class::cast).filter(label -> label.getText().equals("1  second")).findFirst().orElseThrow(); assertTrue(name.getWidth()>0);
                try { var image = new java.awt.image.BufferedImage(frame.get().getWidth(),frame.get().getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB); var g = image.createGraphics(); frame.get().paint(g); g.dispose(); java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/visual-checks")); javax.imageio.ImageIO.write(image,"png",java.nio.file.Path.of("target/visual-checks/ai-model-cards.png").toFile()); }
                catch (Exception failure) { throw new AssertionError(failure); }
            });
        } finally { SwingUtilities.invokeAndWait(() -> frame.get().dispose()); }
    }
    @Test void retainsFailedModelFormAndCancellationWaitsForWorkerCleanup() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        CountDownLatch started = new CountDownLatch(1), cleanup = new CountDownLatch(1), release = new CountDownLatch(1); boolean[] block = {false};
        AiApplicationFacade service = (AiApplicationFacade) Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{AiApplicationFacade.class},(proxy,method,args) -> {
            if (!block[0]) throw gold.debug.windowstolinux.app.service.failure.ApplicationServiceException.create(
                    gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType.AI_CONFIGURATION_TEST_FAILED, "test failed"); started.countDown();
            try { new CountDownLatch(1).await(); } catch (InterruptedException interrupted) { cleanup.countDown(); release.await(); throw new CancellationException(); } return null;
        });
        AtomicReference<AiProviderDialog> dialog = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { FlatLightLaf.setup(); dialog.set(new AiProviderDialog(null,service,c,messages,model("existing",true,1),() -> fail("must not save"))); dialog.get().setModalityType(Dialog.ModalityType.MODELESS); dialog.get().setVisible(true); field(dialog.get(),"save",JButton.class).doClick(); });
        try {
            idle(); SwingUtilities.invokeAndWait(() -> { assertTrue(dialog.get().isShowing()); assertEquals("existing",field(dialog.get(),"name",JTextField.class).getText()); assertTrue(field(dialog.get(),"status",JTextArea.class).getText().contains("模型测试失败")); block[0]=true; field(dialog.get(),"save",JButton.class).doClick(); });
            assertTrue(started.await(3,TimeUnit.SECONDS)); SwingUtilities.invokeAndWait(() -> dialog.get().dispatchEvent(new WindowEvent(dialog.get(),WindowEvent.WINDOW_CLOSING)));
            assertTrue(cleanup.await(3,TimeUnit.SECONDS)); assertTrue(DesktopTaskExecutor.hasActiveTasks()); assertTrue(dialog.get().isDisplayable()); release.countDown(); idle(); assertFalse(dialog.get().isDisplayable());
        } finally { release.countDown(); SwingUtilities.invokeAndWait(() -> dialog.get().dispose()); }
    }
    private static <T> T field(Object target,String name,Class<T> type) { try { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(target)); } catch (Exception e) { throw new AssertionError(e); } }
    private static void idle() throws Exception { long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(30); while (DesktopTaskExecutor.hasActiveTasks() && System.nanoTime()<deadline) Thread.sleep(10); assertFalse(DesktopTaskExecutor.hasActiveTasks()); SwingUtilities.invokeAndWait(() -> {}); }
    private static Stream<Component> descendants(Container root) { return Stream.of(root.getComponents()).flatMap(child -> child instanceof Container nested ? Stream.concat(Stream.of(child),descendants(nested)) : Stream.of(child)); }
}
