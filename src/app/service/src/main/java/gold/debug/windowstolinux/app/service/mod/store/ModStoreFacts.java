package gold.debug.windowstolinux.app.service.mod.store;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.mod.config.ModManifest;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;

/** Shared store facts, distinct from author execution or installation authority. / 共用商店事实，与作者执行及安装权限分开。 */
final class ModStoreFacts {
    /** Stateless JSON tree comparison; manifests were already read strictly. / 无状态 JSON 树比较，清单已由严格读取器核验。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Prevents stateful construction. / 禁止有状态构造。 */
    private ModStoreFacts() {
    }

    /** Validates one exact Mod ID. / 验证一个精确 Mod ID。
     * @param owner actual declared ID / 实际声明 ID */
    static void owner(String owner) {
        if (owner == null || owner.length() > 512 || !owner.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))
            throw new IllegalArgumentException("Invalid Mod identity");
    }

    /** Compares complete declarations ignoring only JSON object order and whitespace. / 比较完整声明，仅忽略 JSON 对象顺序及空白。
     * @param left expected strict declaration / 预期严格声明
     * @param right actual strict declaration / 实际严格声明
     * @return whether every declared field matches / 每个声明字段是否匹配
     * @throws IOException if an already validated declaration became unreadable / 已核验声明变得不可读时 */
    static boolean same(ModManifest left, ModManifest right) throws IOException {
        return JSON.readTree(left.json()).equals(JSON.readTree(right.json()));
    }

    /** Creates a bounded public store problem. / 创建有界公开商店问题。
     * @param code actual problem category / 实际问题类别
     * @param event stable event identity / 稳定事件身份
     * @param detail non-secret explanation / 非秘密说明
     * @return actual problem / 实际问题 */
    static Problem problem(ProblemCode code, String event, String detail) {
        return new Problem(code, event, detail == null ? event : detail.substring(0, Math.min(detail.length(), 2048)),
            Map.of(), Optional.empty());
    }

    /** Checks cooperative cancellation without interrupting installer code. / 检查协作取消，不中断安装器代码。
     * @param cancellation original request / 原请求 */
    static void cancellation(CancelToken cancellation) {
        if (cancellation.requested() || Thread.currentThread().isInterrupted())
            throw new CancellationException("Store operation cancelled");
    }

    /** Returns actual known diagnostics without exposing database messages. / 返回实际已知诊断，不暴露数据库消息。
     * @param failure original failure / 原失败
     * @return bounded problem / 有界问题 */
    static Problem failure(Exception failure) {
        if (failure instanceof ApiException api) return api.problem();
        return problem(failure instanceof CancellationException ? ProblemCode.CANCELLED : ProblemCode.IO_FAILURE,
            failure instanceof CancellationException ? "MOD_STORE_CANCELLED" : "MOD_STORE_FAILED",
            failure instanceof java.sql.SQLException ? "Store persistence could not be read safely" : failure.getMessage());
    }
}
