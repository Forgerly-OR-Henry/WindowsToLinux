package gold.debug.windowstolinux.app.service.file;

import java.util.*;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.appapi.task.result.ApiException;

/** Shares actual original capture ownership across file activations of one engine. / 在同引擎文件启用间共享实际原捕获归属。 */
final class PlatformMaterialCoordinator {
    /** Weak engine keys do not keep closed engines alive. / 弱引擎键不保活已关闭引擎。 */
    private static final Map<PlatformDatabase, PlatformMaterialCoordinator> ENGINES = new WeakHashMap<>();

    /** Exact task/material capture slots. / 精确任务及素材捕获槽。 */
    private final Map<String, Capture> captures = new HashMap<>();

    /** Returns the same actual capture registry for the original engine. / 为原引擎返回同一实际捕获登记。
     * @param database original engine / 原引擎
     * @return engine-local actual custody / 引擎本地实际接管 */
    static synchronized PlatformMaterialCoordinator forEngine(PlatformDatabase database) {
        return ENGINES.computeIfAbsent(database, ignored -> new PlatformMaterialCoordinator());
    }

    /** Returns only the exact original capture slot. / 仅返回精确原捕获槽。
     * @param task original task / 原任务
     * @param material original material / 原素材
     * @return exact shared slot / 精确共享槽 */
    synchronized Capture capture(UUID task, UUID material) {
        return captures.computeIfAbsent(task + "/" + material, ignored -> new Capture());
    }

    /** Actual synchronous capture and original transferred handle. / 实际同步捕获及原转交句柄。 */
    static final class Capture {
        /** Whether original sealing can still mutate payload. / 原封存是否仍可改变载荷。 */
        boolean active;

        /** Original handle retained until acknowledged detach. / 保留至已确认分离的原句柄。 */
        PlatformWorkspaceTransfer transfer;

        /** Removes only the original provider's already detached actual lease. / 仅移除原生产方已分离的实际占用。
         * @throws ApiException if actual original scope cleanup is uncertain / 实际原作用域清理不确定时 */
        void finish() throws ApiException {
            if (transfer != null) {
                transfer.finish().complete();
                transfer = null;
            }
        }
    }
}
