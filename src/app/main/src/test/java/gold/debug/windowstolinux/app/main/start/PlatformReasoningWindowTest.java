package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.net.URI;
import java.util.*;
import javax.swing.*;

import gold.debug.windowstolinux.app.ai.model.*;
import gold.debug.windowstolinux.app.ai.provider.ModelProviderType;
import gold.debug.windowstolinux.app.service.ai.*;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.appapi.ai.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformReasoningWindowTest {
    @TempDir
    Path root;

    @Test
    void realDetailControlsSaveIndependentPurposesAndRestoreRejectedStaleEdit() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var service = PlatformModelServiceTest.setup(runtime);
            service.save(new AiModelProfile("shared", URI.create("https://example.test/responses"), "gpt-6.1-sol", "ai/shared", ModelReasoningLevel.DEFAULT,
                ModelProtocol.OPENAI_RESPONSES, ModelProviderType.OPENAI, Map.of()), "Shared model", "ui-fixture-key".toCharArray(), 4096);
            var frame = PlatformModelsWindowTest.window(runtime, ThemeMode.LIGHT, 100);
            try {
                await(() -> named(frame, "ai.inventory").isEnabled());
                var inventory = (JList<?>) onEdt(() -> named(frame, "ai.inventory"));
                onEdt(() -> {
                    inventory.setSelectedIndex(0);
                    inventory.getActionMap().get("details").actionPerformed(new java.awt.event.ActionEvent(inventory, 0, "details"));
                    return null;
                });
                await(() -> descendants(frame).anyMatch(c -> "models.reasoning.LANGUAGE".equals(c.getName())));
                onEdt(() -> {
                    var language = (JComboBox<?>) named(frame, "models.reasoning.LANGUAGE");
                    assertEquals(AiReasoningMode.MEDIUM, language.getSelectedItem());
                    language.setSelectedItem(AiReasoningMode.LOW);
                    ((JComboBox<?>) named(frame, "models.reasoning.APPROVAL")).setSelectedItem(AiReasoningMode.HIGH);
                    inventory.getActionMap().get("details").actionPerformed(new java.awt.event.ActionEvent(inventory, 0, "details"));
                    descendants(frame).filter(gold.debug.windowstolinux.app.ui.ai.PlatformModelsPane.class::isInstance)
                        .map(gold.debug.windowstolinux.app.ui.ai.PlatformModelsPane.class::cast).findFirst().orElseThrow().localize();
                    assertEquals(AiReasoningMode.LOW, ((JComboBox<?>) named(frame, "models.reasoning.LANGUAGE")).getSelectedItem());
                    return null;
                });
                assertEquals(AiReasoningMode.MEDIUM, service.listAiConfigurations().getFirst().profile().reasoningDefaults().get(AiPurpose.LANGUAGE), "Localization must not write a draft");
                onEdt(() -> {
                    ((JButton) named(frame, "models.reasoning.save")).doClick(0);
                    return null;
                });
                for (int attempt = 0; service.listAiConfigurations().getFirst().profile().reasoningDefaults().get(AiPurpose.APPROVAL) != AiReasoningMode.HIGH; attempt++) {
                    assertTrue(attempt < 100, "The background preference save must complete");
                    Thread.sleep(50);
                }
                await(() -> named(frame, "models.reasoning.save").isEnabled());
                await(() -> ((JComboBox<?>) named(frame, "models.reasoning.LANGUAGE")).getSelectedItem() == AiReasoningMode.LOW);
                assertEquals(AiReasoningMode.MEDIUM, service.listAiConfigurations().getFirst().profile().reasoningDefaults().get(AiPurpose.VISION));
                onEdt(() -> {
                    frame.setSize(1200, 800);
                    frame.validate();
                    var image = new java.awt.image.BufferedImage(frame.getWidth(), frame.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                    var graphics = image.createGraphics();
                    frame.paint(graphics);
                    graphics.dispose();
                    var path = Path.of("target/ai-reasoning-details.png");
                    Files.createDirectories(path.getParent());
                    javax.imageio.ImageIO.write(image, "png", path.toFile());
                    return null;
                });
                var current = service.listAiConfigurations().getFirst();
                service.saveReasoning("shared", current.revision(), Map.of(AiPurpose.LANGUAGE, AiReasoningMode.HIGH,
                    AiPurpose.APPROVAL, AiReasoningMode.HIGH, AiPurpose.VISION, AiReasoningMode.MEDIUM));
                onEdt(() -> {
                    ((JComboBox<?>) named(frame, "models.reasoning.LANGUAGE")).setSelectedItem(AiReasoningMode.MEDIUM);
                    ((JButton) named(frame, "models.reasoning.save")).doClick(0);
                    return null;
                });
                await(() -> !((JLabel) named(frame, "models.reasoning.status")).getText().isEmpty());
                assertEquals(AiReasoningMode.HIGH, service.listAiConfigurations().getFirst().profile().reasoningDefaults().get(AiPurpose.LANGUAGE));
                onEdt(() -> {
                    assertEquals(AiReasoningMode.LOW, ((JComboBox<?>) named(frame, "models.reasoning.LANGUAGE")).getSelectedItem());
                    return null;
                });
            } finally {
                onEdt(() -> {
                    frame.dispose();
                    return null;
                });
            }
        }
    }
}
