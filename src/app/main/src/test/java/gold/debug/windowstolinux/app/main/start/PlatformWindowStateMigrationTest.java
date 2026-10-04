package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.accessibility.AccessibleState;
import javax.swing.*;

import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformTaskAdmission;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.language.LocaleBinding;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.app.ui.mod.PlatformModsPane;
import gold.debug.windowstolinux.app.ui.server.ServerProfileDialog;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.appapi.ui.page.ModPage;
import gold.debug.windowstolinux.appapi.ui.page.PageSpec;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Migrates window and owned-draft contracts through the actual platform shell; the author view is a registry contract fixture, not all deployment CLASS pages. / 经实际平台外壳迁移窗口及从属草稿合同；作者视图是登记合同夹具，不代表全部部署 CLASS 页面。 */
class PlatformWindowStateMigrationTest {
    @TempDir
    Path root;

    @Test
    void actualModEnabledCheckboxKeepsSpaceAccessibilityDisabledAndRestoreWithoutActions() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless(), "Actual Mod management requires the desktop");
        var management = new PlatformModManagementTest();
        management.root = root;
        try {
            management.development(false);
            var actions = new AtomicInteger();
            onEdt(() -> {
                var selected = (JCheckBox) named(management.frame, "mods.enabled");
                var save = (JButton) named(management.frame, "mods.save");
                assertTrue(selected.isEnabled());
                assertFalse(selected.isSelected());
                assertFalse(save.isEnabled());
                assertEquals(MessageCatalog.forLanguageTag("zh-CN").text("mods.enabled"), selected.getAccessibleContext().getAccessibleName());
                selected.addActionListener(event -> actions.incrementAndGet());
                selected.doClick(0);
                assertTrue(selected.isSelected());
                assertTrue(save.isEnabled(), "The actual product callback must update the reviewed-choice action");
                assertEquals(1, actions.get());
                assertTrue(selected.getAccessibleContext().getAccessibleStateSet().contains(AccessibleState.CHECKED));
                for (String key : List.of("pressed SPACE", "released SPACE")) {
                    var binding = selected.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(key));
                    assertNotNull(binding);
                    var action = selected.getActionMap().get(binding);
                    assertNotNull(action);
                    action.actionPerformed(new ActionEvent(selected, ActionEvent.ACTION_PERFORMED, key));
                }
                assertFalse(selected.isSelected());
                assertFalse(save.isEnabled());
                assertEquals(2, actions.get());
                assertFalse(selected.getAccessibleContext().getAccessibleStateSet().contains(AccessibleState.CHECKED));
                ((PlatformModsPane) named(management.frame, "platform.mods")).setLocked(true);
                assertFalse(selected.isEnabled());
                selected.doClick(0);
                assertEquals(2, actions.get());
                ((PlatformModsPane) named(management.frame, "platform.mods")).setLocked(false);
                return null;
            });
            management.idle();
            await(() -> named(management.frame, "mods.enabled").isEnabled());
            onEdt(() -> {
                var selected = (JCheckBox) named(management.frame, "mods.enabled");
                assertFalse(selected.isSelected());
                assertEquals(2, actions.get(), "Reloading the durable original choice must not submit a user action");
                selected.doClick(0);
                assertEquals(3, actions.get());
                var inventory = (JList<?>) named(management.frame, "mods.inventory");
                inventory.clearSelection();
                inventory.setSelectedIndex(0);
                assertFalse(selected.isSelected());
                assertEquals(3, actions.get(), "Product selection restoration must not synthesize an action");
                var english = MessageCatalog.forLanguageTag("en");
                LocaleBinding.refresh(management.frame, english);
                assertEquals(english.text("mods.enabled"), selected.getText());
                assertEquals(english.text("mods.enabled"), selected.getAccessibleContext().getAccessibleName());
                assertEquals(3, actions.get());
                assertFalse(((JButton) named(management.frame, "mods.save")).isEnabled());
                return null;
            });
            var original = management.runtime.modIntents().list().getFirst();
            assertFalse(original.choice().enabled());
            assertTrue(original.operations().isEmpty());
            assertTrue(management.fixture.actions().isEmpty(), "Draft interaction must not start or stop the actual registered CLASS Mod");
        } finally {
            await(() -> !DesktopTaskRunner.hasActiveTasks());
            if (management.runtime != null) management.runtime.close();
            onEdt(() -> {
                if (management.frame != null) management.frame.dispose();
                return null;
            });
        }
    }

    @Test
    void confirmedDisplayChangesKeepOriginalPageTaskDraftsOutputsAndOwnedEditor() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless(), "Actual window state requires the desktop");
        try (var fixture = new WindowFixture(root)) {
            var release = new CountDownLatch(1);
            var entered = new CountDownLatch(1);
            var checkpointClosed = new AtomicBoolean();
            var cancellation = new AtomicReference<CancelToken>();
            var binding = fixture.runtime.tasks().bind(PlatformTaskAdmission.PLATFORM_OWNER, fixture.runtime.version(), false);
            var handle = binding.submit(new TaskRequest("platform.window.state", "Window state contract", List.of(), true), task -> {
                try (var checkpoint = binding.beginCheckpoint(task, "window-state", "{\"view\":\"original\"}")) {
                    cancellation.set(task.cancelToken());
                    entered.countDown();
                    assertTrue(release.await(30, TimeUnit.SECONDS), "Original work must remain owned until explicitly released");
                }
                checkpointClosed.set(true);
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of("original"), Optional.empty());
            });
            try {
                assertTrue(entered.await(8, TimeUnit.SECONDS));
                var id = handle.id();
                for (var choice : List.of(new DesktopDisplayConfig("en", ThemeMode.DARK),
                    new DesktopDisplayConfig("zh-CN", ThemeMode.LIGHT),
                    new DesktopDisplayConfig("en", ThemeMode.LIGHT))) {
                    fixture.confirm(choice);
                    fixture.assertOriginalView();
                    assertEquals(id, handle.id());
                    assertEquals(TaskState.RUNNING, handle.snapshot().state());
                    assertFalse(cancellation.get().requested());
                    assertFalse(handle.completion().toCompletableFuture().isDone());
                    assertFalse(checkpointClosed.get());
                }
            } finally {
                release.countDown();
                handle.completion().toCompletableFuture().get(8, TimeUnit.SECONDS);
            }
            assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(8, TimeUnit.SECONDS).outcome());
            assertTrue(checkpointClosed.get());
            assertEquals(TaskState.ENDED, handle.snapshot().state());
            fixture.closeThroughActualOwner();
            assertTrue(fixture.closedOffEdt.get());
            assertEquals(1, fixture.pageCloses.get());
            assertFalse(onEdt(() -> fixture.editor.isDisplayable()));
            assertEquals(0, onEdt(() -> ((JPasswordField) named(fixture.editor, "server.password")).getPassword().length));
        }
    }

    @Test
    void supportedScaledWindowsKeepActionsLogHelpAndNormalBoundsAfterMaximizedDisplayChange() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless(), "Actual maximization requires the desktop");
        assertTrue(Toolkit.getDefaultToolkit().isFrameStateSupported(Frame.MAXIMIZED_BOTH));
        String oldScale = System.getProperty("flatlaf.uiScale");
        try {
            for (String scale : List.of("1.0", "1.5")) {
                onEdt(() -> {
                    System.setProperty("flatlaf.uiScale", scale);
                    DesktopThemeService.install(ThemeMode.LIGHT);
                    return null;
                });
                try (var fixture = new WindowFixture(root.resolve("scale-" + scale))) {
                    assertEquals(Float.parseFloat(scale), onEdt(UIScale::getUserScaleFactor), 0.02);
                    for (var size : List.of(new Dimension(800, 560), new Dimension(1000, 640))) {
                        onEdt(() -> {
                            fixture.frame.setSize(UIScale.scale(size.width), UIScale.scale(size.height));
                            fixture.frame.validate();
                            for (var component : List.of(fixture.primary, fixture.output, fixture.help)) {
                                assertTrue(component.isShowing());
                                var visible = component.getVisibleRect();
                                assertEquals(component.getWidth(), visible.width, "Primary action, log and help must fit the supported viewport");
                                assertEquals(component.getHeight(), visible.height);
                            }
                            return null;
                        });
                        fixture.assertOriginalView();
                    }
                    var normal = onEdt(() -> new Rectangle(fixture.frame.getBounds()));
                    onEdt(() -> {
                        fixture.frame.setExtendedState(Frame.MAXIMIZED_BOTH);
                        return null;
                    });
                    await(() -> (fixture.frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH);
                    fixture.confirm(new DesktopDisplayConfig("en", ThemeMode.DARK));
                    fixture.assertOriginalView();
                    onEdt(() -> {
                        assertEquals(1, java.util.Arrays.stream(fixture.frame.getOwnedWindows()).filter(value -> value == fixture.editor && value.isDisplayable()).count());
                        fixture.frame.setExtendedState(Frame.NORMAL);
                        return null;
                    });
                    await(() -> fixture.frame.getExtendedState() == Frame.NORMAL);
                    await(() -> fixture.frame.getBounds().equals(normal));
                    assertEquals(normal, onEdt(() -> fixture.frame.getBounds()), "The actual window manager must restore the original normal rectangle");
                    fixture.assertOriginalView();
                    fixture.closeThroughActualOwner();
                }
            }
        } finally {
            onEdt(() -> {
                if (oldScale == null) System.clearProperty("flatlaf.uiScale");
                else System.setProperty("flatlaf.uiScale", oldScale);
                DesktopThemeService.install(ThemeMode.LIGHT);
                return null;
            });
        }
    }

    /** Owns only this test's actual runtime, page scope and windows. / 仅持有本测试的实际运行器、页面作用域及窗口。 */
    private static final class WindowFixture implements AutoCloseable {
        private final PlatformRuntime runtime;

        private final MessageCatalog catalog = MessageCatalog.forLanguageTag("zh-CN");

        private final AtomicInteger creates = new AtomicInteger(), pageCloses = new AtomicInteger();

        private final AtomicReference<UiEnvironment> environment = new AtomicReference<>();

        private final AtomicBoolean closedOffEdt = new AtomicBoolean();

        private final AtomicInteger environments = new AtomicInteger();

        private final PlatformFrame frame;

        private final ServerProfileDialog editor;

        private final ModPageRegistry.PageScope scope;

        private final JPanel page;

        private final JTextField draft;

        private final JTextArea output;

        private final JComboBox<String> selection;

        private final JCheckBox choice;

        private final JButton primary, help;

        private volatile boolean closed;

        private WindowFixture(Path data) throws Exception {
            runtime = PlatformRuntime.open(data, PlatformStartupTest.PROTECTION);
            var servers = PlatformServerServiceTest.setup(runtime);
            runtime.started();
            frame = onEdt(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT);
                var window = new PlatformFrame(runtime.security(), catalog, ThemePalette.light(),
                    new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false), FailureReportStore.disabled());
                window.installAppearance(new DesktopDisplayConfig("zh-CN", ThemeMode.LIGHT), requested -> {
                    try (var authorized = runtime.security().beginTask()) {
                        authorized.execute(() -> {
                            runtime.persistence().preferences().saveAll(Map.of("ui_locale", requested.localeTag(), "ui_theme", requested.themeMode().name()));
                            return null;
                        });
                    }
                });
                window.setLocation(10, 10);
                window.setSize(UIScale.scale(1000), UIScale.scale(640));
                window.setVisible(true);
                return window;
            });
            var widgets = onEdt(() -> {
                var factory = new DesktopPartFactory(ThemePalette.light());
                var root = factory.pagePanel();
                root.setName("migration.contract.page");
                root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
                var field = new JTextField("Unsubmitted source draft");
                var options = new JComboBox<>(new String[]{"first", "selected", "third"});
                options.setSelectedIndex(1);
                var enabled = new JCheckBox("Explicit choice", true);
                var form = factory.transparent(new GridLayout(0, 1, 8, 8));
                form.add(field);
                form.add(options);
                form.add(enabled);
                root.add(form, BorderLayout.NORTH);
                var log = new JTextArea("Original non-secret output\nSecond original line", 8, 20);
                log.setEditable(false);
                root.add(log, BorderLayout.CENTER);
                var actions = factory.transparent(new FlowLayout(FlowLayout.LEADING));
                var submit = new PageMessagePresenter(catalog).bind(factory.primaryButton(""), "appearance.save");
                var guidance = factory.secondaryButton("?");
                guidance.getAccessibleContext().setAccessibleName(catalog.text("help.field.sshPort"));
                actions.add(submit);
                actions.add(guidance);
                root.add(actions, BorderLayout.SOUTH);
                return new Object[]{root, field, log, options, enabled, submit, guidance};
            });
            page = (JPanel) widgets[0];
            draft = (JTextField) widgets[1];
            output = (JTextArea) widgets[2];
            @SuppressWarnings("unchecked") var selected = (JComboBox<String>) widgets[3];
            selection = selected;
            choice = (JCheckBox) widgets[4];
            primary = (JButton) widgets[5];
            help = (JButton) widgets[6];
            scope = frame.pages().begin("example.window.contract", false);
            scope.register(new PageSpec("contract", "contract", "Contract fixture", false), () -> new ModPage() {
                public JComponent create(UiEnvironment current) {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    creates.incrementAndGet();
                    environment.set(current);
                    return page;
                }

                public void environmentChanged(UiEnvironment current) {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    environments.incrementAndGet();
                    environment.set(current);
                }

                public void close() {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    pageCloses.incrementAndGet();
                }
            });
            scope.commit();
            scope.open("contract");
            editor = onEdt(() -> {
                var owned = new ServerProfileDialog(frame, servers, new DesktopPartFactory(ThemePalette.light()), new PageMessagePresenter(catalog),
                    new ServerProfile("existing", "original.example", 22, "tester", "ssh/existing", "Original name"), ignored -> fail("Appearance must not save a server draft"));
                owned.setModalityType(Dialog.ModalityType.MODELESS);
                owned.setVisible(true);
                ((JTextField) named(owned, "server.name")).setText("Unsubmitted editor draft");
                ((JPasswordField) named(owned, "server.password")).setText("temporary-input");
                frame.onExit(() -> {
                    closedOffEdt.set(!SwingUtilities.isEventDispatchThread());
                    scope.close();
                    runtime.close();
                    closed = true;
                });
                return owned;
            });
        }

        private void confirm(DesktopDisplayConfig requested) throws Exception {
            int before = environments.get();
            onEdt(() -> {
                ((JComboBox<?>) named(frame, "appearance.language")).setSelectedItem(requested.localeTag());
                ((JComboBox<?>) named(frame, "appearance.theme")).setSelectedItem(requested.themeMode());
                ((JButton) named(frame, "appearance.save")).doClick(0);
                return null;
            });
            await(() -> environments.get() > before && named(frame, "appearance.save").isEnabled());
            assertEquals(Optional.of(requested.localeTag()), runtime.persistence().preferences().find("ui_locale"));
            assertEquals(Optional.of(requested.themeMode().name()), runtime.persistence().preferences().find("ui_theme"));
            assertEquals(Locale.forLanguageTag(requested.localeTag()), environment.get().locale());
            assertEquals(requested.themeMode().name(), environment.get().themeId());
            assertFalse(environment.get().locked());
        }

        private void assertOriginalView() throws Exception {
            onEdt(() -> {
                assertEquals(1, creates.get());
                assertEquals(0, pageCloses.get());
                assertSame(page, named(frame, "migration.contract.page"));
                assertTrue(page.isShowing());
                assertSame(page, ((JTabbedPane) page.getParent()).getSelectedComponent());
                assertEquals("Unsubmitted source draft", draft.getText());
                assertEquals("Original non-secret output\nSecond original line", output.getText());
                assertEquals(1, selection.getSelectedIndex());
                assertTrue(choice.isSelected());
                assertEquals(catalog.text("appearance.save"), primary.getText());
                assertEquals("Unsubmitted editor draft", ((JTextField) named(editor, "server.name")).getText());
                assertEquals("original.example", ((JTextField) named(editor, "server.host")).getText());
                char[] password = ((JPasswordField) named(editor, "server.password")).getPassword();
                try {
                    assertArrayEquals("temporary-input".toCharArray(), password);
                } finally {
                    java.util.Arrays.fill(password, '\0');
                }
                assertEquals(catalog.text("server.password.keep"), ((JPasswordField) named(editor, "server.password")).getToolTipText());
                assertEquals(catalog.text("server.edit"), editor.getTitle());
                assertTrue(editor.isDisplayable());
                assertSame(frame, editor.getOwner());
                return null;
            });
        }

        private void closeThroughActualOwner() throws Exception {
            onEdt(() -> {
                frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
                return null;
            });
            await(() -> !frame.isDisplayable() && !editor.isDisplayable()
                && ((JPasswordField) named(editor, "server.password")).getPassword().length == 0);
        }

        @Override
        public void close() throws Exception {
            if (!closed) {
                scope.close();
                runtime.close();
                closed = true;
            }
            onEdt(() -> {
                editor.dispose();
                frame.dispose();
                return null;
            });
        }
    }
}
