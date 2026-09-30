package gold.debug.windowstolinux.mod.deployment.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.*;

import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.appapi.ui.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentReferenceFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticPageService;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticWorkflow;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input.DeploymentStaticRuntimeResolver;
import org.junit.jupiter.api.Test;

/** Exercises the actual owner modal without replacing immutable original decisions. / 在真实所属模态框中保留不可变原决定。 */
class DeploymentBuildCorrectionPageTest extends DeploymentRuntimeFixture {
    @Test
    void actualFourFieldCorrectionKeepsCustomLimitsFilesRisksAndEveryOtherOriginalValue() throws Exception {
        enable();
        var window = new AtomicReference<JFrame>();
        var page = new AtomicReference<DeploymentStaticPage>();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var workflow = new DeploymentStaticWorkflow(context, new DeploymentReferenceFlow(context));
            SwingUtilities.invokeAndWait(() -> {
                var actual = new DeploymentStaticPage(new DeploymentStaticPageService(context, workflow));
                var owner = new JFrame("Actual four-field build correction");
                owner.setContentPane(actual.create(new UiEnvironment(Locale.ENGLISH, "LIGHT", false)));
                owner.pack();
                owner.setVisible(true);
                page.set(actual);
                window.set(owner);
            });
            var values = new LinkedHashMap<String, String>();
            values.put("primary", "app.jar");
            values.put("secondary", "example.Main");
            values.put("version", "1.9.25");
            values.put("jvmTarget", "17");
            values.put("port", "8080");
            values.put("healthMode", "TCP");
            values.put("exposure", "INTERNAL");
            values.put("configuration", "");
            values.put("secrets", "");
            values.put("databaseMode", "NONE");
            var file = new DeploymentFileBinding("uploads",
                    new DeploymentStoragePath("uploads", DeploymentStoragePath.AccessMode.READ_WRITE, "uploads-v1",
                            true),
                    DeploymentStorageLocation.custom("/srv/reviewed-uploads"),
                    DeploymentStorageLocation.StorageResourceType.FILE);
            var limits = new DeploymentBuildLimits(600, 32, 512, 65536, 128L * 1024 * 1024, false);
            var original = new DeploymentStaticWorkflow.Answers(values, List.of(file), limits, true, true);
            var fields = List.of("primary", "secondary", "version", "jvmTarget").stream()
                    .map(key -> DeploymentStaticRuntimeResolver.field("worker", key, values.get(key), List.of()))
                    .toList();
            var prompt = new DeploymentStaticWorkflow.Prompt("worker", "demo", DeploymentProjectKind.KOTLIN_SERVICE,
                    values, fields);
            workflow.validateReview(prompt, original);
            var expected = new LinkedHashMap<>(values);
            expected.putAll(Map.of("primary", "changed.jar", "secondary", "example.ChangedMain", "version", "2.0.0",
                    "jvmTarget", "21"));
            workflow.validateReview(prompt,
                    new DeploymentStaticWorkflow.Answers(expected, original.files(), limits, true, true));
            var observed = new AtomicReference<OperationResult<DeploymentStaticWorkflow.Answers>>();
            var handle = context.tasks().submit(
                    new TaskRequest("build-correction-page-test", "Original build correction", List.of(), true),
                    task -> {
                        var result = new DeploymentStaticPageReviewer(page.get(), page.get().viewGeneration())
                                .reviewBuildCorrection(new ServerRef("reviewed-server"), "a".repeat(64), prompt,
                                        original, task);
                        observed.set(result);
                        return result;
                    });
            var dialog = awaitModal(window.get());
            SwingUtilities.invokeAndWait(() -> {
                assertSame(window.get(), dialog.getOwner());
                var controls = descendants(dialog);
                assertEquals(4, controls.stream().filter(JTextField.class::isInstance).count());
                assertEquals(0, controls.stream().filter(JCheckBox.class::isInstance).count());
                var replacements = Map.of("primary", "changed.jar", "secondary", "example.ChangedMain", "version",
                        "2.0.0", "jvmTarget", "21");
                replacements
                        .forEach((key,
                                value) -> ((JTextField) controls.stream()
                                        .filter(control -> key.equals(control.getName())).findFirst().orElseThrow())
                                        .setText(value));
                var option = (JOptionPane) controls.stream().filter(JOptionPane.class::isInstance).findFirst()
                        .orElseThrow();
                option.setValue(JOptionPane.OK_OPTION);
                dialog.dispose();
            });
            OperationResult<DeploymentStaticWorkflow.Answers> result;
            try {
                result = handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException failed) {
                throw new AssertionError(
                        "Original correction task=" + handle.snapshot() + "; callback=" + observed.get(), failed);
            }
            assertEquals(Outcome.SUCCEEDED, result.outcome(), result.problem().toString());
            var corrected = result.value().orElseThrow();
            assertEquals(expected, corrected.values());
            assertEquals(original.files(), corrected.files());
            assertSame(limits, corrected.limits());
            assertTrue(corrected.containerRisk());
            assertTrue(corrected.adapterRisk());
            assertEquals(values, original.values());
            SwingUtilities.invokeAndWait(() -> {
                page.get().close();
                window.get().dispose();
            });
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (page.get() != null)
                    page.get().close();
                if (window.get() != null)
                    window.get().dispose();
            });
        }
    }

    private JDialog awaitModal(JFrame owner) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        var visible = new AtomicReference<JDialog>();
        do {
            SwingUtilities.invokeAndWait(() -> {
                for (var window : owner.getOwnedWindows())
                    if (window instanceof JDialog dialog && dialog.isVisible())
                        visible.set(dialog);
            });
            if (visible.get() != null)
                return visible.get();
            Thread.sleep(20);
        } while (System.nanoTime() < until);
        throw new AssertionError("Actual original-task correction dialog did not open");
    }

    private List<Component> descendants(Container root) {
        var result = new ArrayList<Component>();
        for (var child : root.getComponents()) {
            result.add(child);
            if (child instanceof Container nested)
                result.addAll(descendants(nested));
        }
        return result;
    }
}
