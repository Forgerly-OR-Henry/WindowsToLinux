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
            assertTrue(((JLabel)selector.getComponent(1)).getText().contains("极高价值"));
            selector.setBusy(false);assertTrue(slider.isEnabled());
        });
    }
}
