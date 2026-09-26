package gold.debug.windowstolinux.app.ui.shell;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.swing.*;

import gold.debug.windowstolinux.app.ui.shell.ModPageRegistry.*;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.ui.*;
import org.junit.jupiter.api.Test;

class ModPageRegistryTest {
    static final UiEnvironment INITIAL = new UiEnvironment(Locale.ENGLISH, "light", false);
    PageSpec spec(String id, boolean settings) {
        return new PageSpec(id, "fixture." + id, "Fixture " + id, settings);
    }
    static class Host implements PageHost {
        final Map<PageKey, JComponent> views = new LinkedHashMap<>();

        int focuses;

        int removeFailures;

        boolean locked;
        public void show(PageKey key, PageSpec spec, JComponent view) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            views.put(key, view);
        }

        public void focus(PageKey key) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            assertTrue(views.containsKey(key));
            focuses++;
        }

        public void remove(PageKey key) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            if (removeFailures-- > 0)
                throw new IllegalStateException("fixture removal failure");
            views.remove(key);
        }

        public void environment(UiEnvironment environment) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            locked = environment.locked();
        }
    }

    static class Page implements ModPage {
        int creates;

        int closes;

        int closeFailures;

        boolean createFailure;

        boolean notifyFailure;

        final List<UiEnvironment> environments = new ArrayList<>();
        public JComponent create(UiEnvironment environment) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            creates++;
            if (createFailure)
                throw new IllegalStateException("fixture create failure");
            return new JPanel();
        }

        public void environmentChanged(UiEnvironment environment) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            environments.add(environment);
            if (notifyFailure)
                throw new IllegalStateException("fixture notification failure");
        }

        public void close() {
            assertTrue(SwingUtilities.isEventDispatchThread());
            closes++;
            if (closeFailures-- > 0)
                throw new IllegalStateException("fixture close failure");
        }
    }

    @Test
    void startupContributionsStayPrivateAndPageInstancesAreLazyAndFreshOnReopen() throws Exception {
        var host = new Host();
        var registry = new ModPageRegistry(host, INITIAL);
        var created = new ArrayList<Page>();
        try (var owner = registry.begin("example.owner", false); var other = registry.begin("example.other", false)) {
            owner.register(spec("main", false), () -> {
                assertTrue(SwingUtilities.isEventDispatchThread());
                var page = new Page();
                created.add(page);
                return page;
            });
            other.register(spec("main", false), Page::new);
            assertTrue(registry.contributions().isEmpty());
            assertTrue(created.isEmpty());
            assertThrows(ApiException.class, () -> owner.open("main"));
            assertThrows(ApiException.class, () -> owner.register(spec("main", false), Page::new));
            owner.commit();
            other.commit();
            assertEquals(2, registry.contributions().size());
            assertTrue(host.views.isEmpty());
            owner.open("main");
            owner.open("main");
            assertEquals(1, created.size());
            assertEquals(1, host.focuses);
            registry.closeView(new PageKey("example.owner", "main"));
            assertEquals(1, created.getFirst().closes);
            owner.open("main");
            assertEquals(2, created.size());
            assertEquals(1, host.views.size());
        }
        assertTrue(registry.contributions().isEmpty());
        assertTrue(host.views.isEmpty());
        assertEquals(1, created.getLast().closes);
    }

    @Test
    void failedStartupAndRecoveryModeDoNotPublishOrCreateBusinessViews() throws Exception {
        var registry = new ModPageRegistry(new Host(), INITIAL);
        var creates = new AtomicInteger();
        try (var failed = registry.begin("example.owner", false)) {
            failed.register(spec("main", false), () -> {
                creates.incrementAndGet();
                return new Page();
            });
        }
        try (var recovery = registry.begin("example.owner", true)) {
            recovery.register(spec("recovery", false), () -> {
                creates.incrementAndGet();
                return new Page();
            });
            recovery.commit();
            assertTrue(registry.contributions().isEmpty());
            assertThrows(ApiException.class, () -> recovery.open("recovery"));
            assertThrows(ApiException.class, () -> registry.begin("example.owner", false));
        }
        assertEquals(0, creates.get());
        try (var normal = registry.begin("example.owner", false)) {
            normal.commit();
        }
    }

    @Test
    void failedCreationCleanupRetainsTheInstanceUntilAnExplicitCloseRetry() throws Exception {
        var registry = new ModPageRegistry(new Host(), INITIAL);
        var page = new Page();
        page.createFailure = true;
        page.closeFailures = 1;
        var factories = new AtomicInteger();
        var owner = registry.begin("example.owner", false);
        owner.register(spec("main", false), () -> {
            factories.incrementAndGet();
            return page;
        });
        owner.commit();
        assertThrows(ApiException.class, () -> owner.open("main"));
        assertEquals(1, page.closes);
        assertThrows(ApiException.class, () -> owner.open("main"));
        assertEquals(1, factories.get());
        owner.close();
        owner.close();
        assertEquals(2, page.closes);
        assertTrue(registry.contributions().isEmpty());
    }

    @Test
    void hostRemovalRetryDoesNotRepeatSuccessfulAuthorClose() throws Exception {
        var host = new Host();
        var registry = new ModPageRegistry(host, INITIAL);
        var page = new Page();
        var owner = registry.begin("example.owner", false);
        var registration = owner.register(spec("main", false), () -> page);
        owner.commit();
        owner.open("main");
        host.removeFailures = 1;
        assertThrows(ApiException.class, registration::close);
        assertEquals(1, page.closes);
        assertEquals(1, host.views.size());
        assertThrows(ApiException.class, () -> owner.open("main"));
        registration.close();
        registration.close();
        assertEquals(1, page.closes);
        assertTrue(host.views.isEmpty());
        owner.close();
    }

    @Test
    void hostLocksBeforeNotificationsAndAFailingPageDoesNotSkipTheOthers() throws Exception {
        var host = new Host();
        var registry = new ModPageRegistry(host, INITIAL);
        var first = new Page();
        first.notifyFailure = true;
        var second = new Page() {
            @Override
            public void environmentChanged(UiEnvironment environment) {
                assertTrue(host.locked);
                super.environmentChanged(environment);
            }
        };
        try (var owner = registry.begin("example.owner", false)) {
            owner.register(spec("first", false), () -> first);
            owner.register(spec("second", false), () -> second);
            owner.commit();
            owner.open("first");
            owner.open("second");
            var changed = new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "dark", true);
            assertThrows(ApiException.class, () -> registry.environment(changed));
            assertEquals(List.of(changed), second.environments);
            assertEquals(changed, owner.environment());
            assertThrows(ApiException.class, () -> owner.open("second"));
        }
    }

    @Test
    void interruptedBackgroundRegistrationReturnsTheActualCommittedIdentity() throws Exception {
        var registry = new ModPageRegistry(new Host(), INITIAL);
        try (var owner = registry.begin("example.owner", false)) {
            var done = new CompletableFuture<Boolean>();
            var thread = new Thread(() -> {
                try {
                    Thread.currentThread().interrupt();
                    var registration = owner.register(spec("main", false), Page::new);
                    done.complete(registration.id().equals("main") && Thread.currentThread().isInterrupted());
                } catch (Throwable failure) {
                    done.completeExceptionally(failure);
                }
            }, "page-registration-test");
            thread.start();
            assertTrue(done.get(5, TimeUnit.SECONDS));
            thread.join(5000);
            assertFalse(thread.isAlive());
            owner.commit();
            assertEquals(1, registry.contributions().size());
        }
    }

    @Test
    void actualSwingHostSeparatesBusinessAndOwnerSettingsAndCloseButtonReleasesView() throws Exception {
        var pane = new AtomicReference<ExtensionPagePane>();
        var registry = new AtomicReference<ModPageRegistry>();
        var closeFailure = new AtomicReference<Exception>();
        SwingUtilities.invokeAndWait(() -> pane.set(new ExtensionPagePane(key -> {
            try {
                registry.get().closeView(key);
            } catch (ApiException failure) {
                closeFailure.set(failure);
            }
        }, owner -> "Owner " + owner, () -> "Locked")));
        registry.set(new ModPageRegistry(pane.get(), INITIAL));
        var business = new Page();
        var settings = new Page();
        try (var owner = registry.get().begin("example.owner", false)) {
            owner.register(spec("business", false), () -> business);
            owner.register(spec("settings", true), () -> settings);
            owner.commit();
            owner.open("business");
            owner.open("settings");
            SwingUtilities.invokeAndWait(() -> {
                var businessTabs = components(pane.get(), JTabbedPane.class);
                assertEquals(1, businessTabs.getFirst().getTabCount());
                var settingTabs = components(pane.get().settingsComponent(), JTabbedPane.class);
                assertEquals(2, settingTabs.size());
                assertEquals("Owner example.owner", settingTabs.getFirst().getTitleAt(0));
                components(businessTabs.getFirst().getTabComponentAt(0), JButton.class).getFirst().doClick();
                assertEquals(0, businessTabs.getFirst().getTabCount());
            });
            assertNull(closeFailure.get());
            assertEquals(1, business.closes);
            registry.get().environment(new UiEnvironment(Locale.ENGLISH, "dark", true));
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(components(pane.get(), JTabbedPane.class).getFirst().isVisible());
                assertFalse(components(pane.get().settingsComponent(), JTabbedPane.class).getFirst().isVisible());
            });
        }
        assertEquals(1, settings.closes);
    }

    @Test
    void aPageCannotMountAfterRevokingItsOwnRegistrationDuringCreation() throws Exception {
        var host = new Host();
        var registry = new ModPageRegistry(host, INITIAL);
        var registration = new AtomicReference<PageRegistration>();
        var page = new Page() {
            @Override
            public JComponent create(UiEnvironment environment) {
                var component = super.create(environment);
                try {
                    registration.get().close();
                } catch (ApiException failure) {
                    throw new IllegalStateException(failure);
                }
                return component;
            }
        };
        try (var owner = registry.begin("example.owner", false)) {
            registration.set(owner.register(spec("main", false), () -> page));
            owner.commit();
            assertThrows(ApiException.class, () -> owner.open("main"));
            assertEquals(1, page.closes);
            assertTrue(host.views.isEmpty());
            assertTrue(registry.contributions().isEmpty());
        }
    }

    @Test
    void recursiveAuthorCloseIsRejectedWithoutLosingRetryableOwnership() throws Exception {
        var host = new Host();
        var registry = new ModPageRegistry(host, INITIAL);
        var registration = new AtomicReference<PageRegistration>();
        var recurse = new AtomicBoolean(true);
        var page = new Page() {
            @Override
            public void close() {
                super.close();
                if (recurse.get())
                    try {
                        registration.get().close();
                    } catch (ApiException failure) {
                        throw new IllegalStateException(failure);
                    }
            }
        };
        try (var owner = registry.begin("example.owner", false)) {
            registration.set(owner.register(spec("main", false), () -> page));
            owner.commit();
            owner.open("main");
            assertThrows(ApiException.class, registration.get()::close);
            assertEquals(1, page.closes);
            assertEquals(1, host.views.size());
            recurse.set(false);
            registration.get().close();
            assertEquals(2, page.closes);
            assertTrue(host.views.isEmpty());
        }
    }

    static <T extends Component> List<T> components(Component root, Class<T> type) {
        var found = new ArrayList<T>();
        if (type.isInstance(root))
            found.add(type.cast(root));
        if (root instanceof Container container)
            for (var child : container.getComponents())
                found.addAll(components(child, type));
        return found;
    }
}
