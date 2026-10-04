package gold.debug.windowstolinux.mod.deployment;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;

import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.model.TaskState;

import java.util.concurrent.TimeUnit;
import java.util.*;

import javax.swing.JComponent;

import gold.debug.windowstolinux.app.db.init.DatabaseProtection;
import gold.debug.windowstolinux.app.db.schema.SchemaAccessScope;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.mod.PlatformModIntents;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflict;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflictChoice;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferProgress;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferStall;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferStallChoice;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.appapi.ui.page.PageSpec;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentSchema;
import org.junit.jupiter.api.io.TempDir;

public class DeploymentRuntimeFixture {
    protected static final String OWNER = DeploymentSchema.OWNER;

    protected static final String VERSION = "1.1.0-dev.1";

    protected static final String PASSWORD = "deployment-isolated-password";

    protected static final DatabaseProtection PROTECTION = new DatabaseProtection() {
        public byte[] protect(byte[] input) {
            byte[] output = input.clone();
            for (int i = 0; i < output.length; i++)
                output[i] ^= 0x67;
            return output;
        }

        public byte[] unprotect(byte[] input) {
            return protect(input);
        }
    };

    @TempDir
    protected Path root;

    /** Retains the existing twenty-second recovery bound. / 保留既有二十秒恢复限时。 */
    protected static TaskSnapshot reconcileReady(PlatformRuntime runtime, TaskHandle<?> handle) throws Exception {
        return reconcileReady(runtime, handle, 20);
    }

    /** Waits only for host refusal before any reconciliation dispatch, within the original bound. / 仅等待未派发的宿主拒绝结束，不扩大原限时。 */
    protected static TaskSnapshot reconcileReady(PlatformRuntime runtime, TaskHandle<?> handle, int seconds) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (true) {
            long remaining = until - System.nanoTime();
            assertTrue(remaining > 0, "The original callback must return within the existing reconciliation bound");
            try {
                return runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(remaining, TimeUnit.NANOSECONDS);
            } catch (ApiException refusal) {
                if (refusal.problem().code() != ProblemCode.BUSY || !refusal.problem().eventCode().equals("TASK_RECOVERY_BUSY"))
                    throw refusal;
                assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
                Thread.sleep(20);
            }
        }
    }

    protected Path workspace() throws Exception {
        Path path = Path.of("").toRealPath();
        while (path != null && !Files.isRegularFile(path.resolve("src/mod/deployment/mod.json")))
            path = path.getParent();
        return Objects.requireNonNull(path, "Actual reactor workspace is required");
    }

    protected PlatformRuntime open() throws Exception {
        var runtime = PlatformRuntime.open(root.resolve("data"), PROTECTION);
        if (runtime.security().securityState().configured())
            runtime.security().unlock(PASSWORD.toCharArray());
        else
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
        runtime.setInteractionPresenter(interactionPrompts());
        runtime.setSourcePicker((title, task, cancel) -> selectedSourceDirectory());
        runtime.started();
        return runtime;
    }

    /** Supplies the presenter before the real platform installs it exactly once. / 在真实平台恰好安装一次前提供交互呈现器。
     * @return default refusal presenter or a scenario's controlled presenter / 默认拒绝呈现器或场景受控呈现器 */
    protected PlatformInteractionService.Prompts interactionPrompts() {
        return new PlatformInteractionService.Prompts() {
            public TransferConflictChoice transferConflict(TransferConflict conflict, ServerId server,
                                                           TaskSnapshot task, CancelToken cancellation) {
                throw new AssertionError("No remote interaction expected");
            }

            public TransferStallChoice transferStalled(TransferStall stall, ServerId server, TaskSnapshot task,
                                                       CancelToken cancellation) {
                throw new AssertionError("No remote interaction expected");
            }

            public String transferProgress(TransferProgress progress) {
                throw new AssertionError("No remote interaction expected");
            }

            public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken cancellation) {
                throw new AssertionError("No external action expected");
            }

            public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                               CancelToken cancellation) {
                throw new AssertionError("No input expected");
            }
        };
    }

    protected Path selectedSourceDirectory() {
        throw new AssertionError("No source selection expected");
    }

    protected void start(PlatformRuntime runtime, boolean enabled) throws Exception {
        var pages = new ModPageRegistry(new ModPageRegistry.PageHost() {
            public void show(ModPageRegistry.PageKey key, PageSpec spec, JComponent component) {
                throw new AssertionError("No deployment page yet");
            }

            public void focus(ModPageRegistry.PageKey key) {
            }

            public void remove(ModPageRegistry.PageKey key) {
            }

            public void environment(UiEnvironment environment) {
            }
        }, new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
        var report = runtime.startDevelopmentMods(workspace(), pages);
        assertTrue(report.problems().isEmpty(), report.problems().toString());
        assertEquals(enabled ? Set.of(OWNER) : Set.of(), report.running());
    }

    protected void enable() throws Exception {
        try (var runtime = open()) {
            start(runtime, false);
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM mod_schema"));
            assertEquals(1, scalar(runtime,
                "SELECT COUNT(*) FROM mod_version WHERE mod_id='" + OWNER + "' AND origin_kind='CLASS'"));
            var intents = runtime.modIntents();
            intents.apply(
                intents.review(OWNER, new PlatformModIntents.Choice(true, Optional.of(VERSION)), Optional.empty()),
                false);
        }
    }

    protected SchemaAccessScope scope(PlatformRuntime runtime, boolean readOnly) throws Exception {
        return new SchemaAccessScope(runtime.persistence().database(),
            runtime.modContexts().get(OWNER).context().db().identity(), UUID.randomUUID(), readOnly);
    }

    protected long scalar(PlatformRuntime runtime, String query) throws Exception {
        try (var connection = runtime.persistence().database().openConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery(query)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

}
