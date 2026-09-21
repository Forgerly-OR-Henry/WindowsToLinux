package gold.debug.windowstolinux.app.ui.deployment.single;
import gold.debug.windowstolinux.app.ui.i18n.*;
import gold.debug.windowstolinux.shared.model.deployment.*;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
class DeploymentModeSelectorTest {
    @Test void standardThreeTicksDefaultToStaticAndApprovalDefaultsToAutomatic() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var selector=new DeploymentModeSelector(new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN")));
            assertEquals(DeploymentAutomationMode.STATIC,selector.mode());assertEquals(AgentApprovalMode.AUTOMATIC,selector.approval());
            JSlider slider=(JSlider)((JPanel)selector.getComponent(0)).getComponent(0);
            assertEquals(0,slider.getMinimum());assertEquals(2,slider.getMaximum());assertTrue(slider.getSnapToTicks());assertTrue(slider.getPaintLabels());
            selector.restore(DeploymentAutomationMode.AGENT,AgentApprovalMode.MANUAL_REVIEW);
            assertEquals(AgentApprovalMode.MANUAL_REVIEW,selector.approval());selector.setBusy(true);assertFalse(slider.isEnabled());
            assertTrue(((JTextArea)selector.getComponent(1)).getText().contains("极高价值"));
            selector.setBusy(false);assertTrue(slider.isEnabled());assertTrue(selector.getPreferredSize().width<500,"manual hint must not force the deployment button off screen");
            selector.setSize(470,135);selector.doLayout();((JPanel)selector.getComponent(0)).doLayout();
            try{var image=new java.awt.image.BufferedImage(470,135,java.awt.image.BufferedImage.TYPE_INT_RGB);var graphics=image.createGraphics();graphics.setColor(UIManager.getColor("Panel.background"));graphics.fillRect(0,0,470,135);selector.paint(graphics);graphics.dispose();var path=java.nio.file.Path.of("target/visual-checks/mode-agent-manual.png");java.nio.file.Files.createDirectories(path.getParent());javax.imageio.ImageIO.write(image,"png",path.toFile());}catch(java.io.IOException failure){throw new AssertionError(failure);}
        });
    }
}
