package gold.debug.windowstolinux.app.main.mod;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.HexFormat;

import gold.debug.windowstolinux.app.db.PlatformDatabase;
import gold.debug.windowstolinux.app.db.mod.ModSchemaReconciliation;
import gold.debug.windowstolinux.app.db.schema.*;
import gold.debug.windowstolinux.app.mod.loader.ModClassLoader;
import gold.debug.windowstolinux.appapi.task.Outcome;

/** Owns one explicit read-only inspection and retries only its original cleanup. / 持有一次明确只读检查，仅重试其原清理。 */
final class ModSchemaInspection implements AutoCloseable {
    /** Original physical engine. / 原物理引擎。 */
    private final PlatformDatabase database;

    /** Original reconciliation ledger. / 原核对账本。 */
    private final ModSchemaReconciliation ledger;

    /** Exact reviewed source, target and program. / 精确已审阅来源、目标及程序。 */
    private final ModSchemaReconciliation.Review review;

    /** Isolated fixed validator loader. / 隔离固定验证器加载器。 */
    private final ModClassLoader loader;

    /** Original persisted attempt, absent before claim. / 原持久尝试，取得前为空。 */
    private ModSchemaReconciliation.Attempt attempt;

    /** Original tracked SELECT-only target access. / 原受跟踪只读目标访问。 */
    private SchemaAccessScope reader;

    /** Observed classification independent of cleanup. / 独立于清理的已观察分类。 */
    private Outcome outcome = Outcome.UNKNOWN;

    /** Successful author and structural receipt. / 成功作者及结构凭据。 */
    private String digest;

    /** Whether original source preservation was checked after all callbacks. / 全部回调之后是否已检查原来源保全。 */
    private boolean sourceChecked;

    /** Whether this explicit action already ran. / 此明确动作是否已运行。 */
    private boolean attempted;

    /** Whether original physical and durable release completed. / 原物理及持久释放是否完成。 */
    private boolean closed;

    /** Whether physical cleanup finished before a possibly uncertain terminal commit. / 可能不确定的终态提交之前物理清理是否已完成。 */
    private boolean cleanupDone;

    /** Retains the exact validator before any author callback. / 在任意作者回调之前保留精确验证器。
     * @param database original engine / 原引擎
     * @param ledger original ledger / 原账本
     * @param review original evidence / 原证据
     * @param loader isolated verified program / 隔离已验证程序 */
    ModSchemaInspection(PlatformDatabase database, ModSchemaReconciliation ledger,
            ModSchemaReconciliation.Review review, ModClassLoader loader) {
        this.database = database;
        this.ledger = ledger;
        this.review = review;
        this.loader = loader;
    }

    /** Validates actual target once, never invoking initialize or migrate. / 验证实际目标一次，绝不调用初始化或迁移。
     * @return observed classification after actual cleanup / 实际清理后的已观察分类
     * @throws Exception if original release or acknowledgement remains / 原释放或确认仍残留时 */
    Outcome inspect() throws Exception {
        if (attempted)
            throw new IllegalStateException("Inspection cannot replay author validation");
        attempted = true;
        try {
            attempt = ledger.begin(review);
            ledger.cleanup(attempt, false);
            if (sourceMatches()) {
                if (!ledger.targetExists(attempt))
                    outcome = Outcome.FAILED;
                else {
                    reader = SchemaAccessScope.reconciliation(database, review.plan().target(), attempt.reader(),
                            attempt.operation());
                    var report = ModSchemaPreparation.lifecycle(loader).validate(reader);
                    if (report != null && !report.valid())
                        outcome = Outcome.FAILED;
                    else if (report != null && report.businessDigest()
                            .map(v -> v.getBytes(StandardCharsets.UTF_8).length <= 4096).orElse(true)) {
                        try {
                            String structural = SchemaValidation.validate(reader);
                            digest = HexFormat.of()
                                    .formatHex(MessageDigest.getInstance("SHA-256")
                                            .digest((structural + "\n" + report.businessDigest().orElse(""))
                                                    .getBytes(StandardCharsets.UTF_8)));
                            outcome = Outcome.SUCCEEDED;
                        } catch (SQLException invalid) {
                            if (invalid.getSQLState() != null && invalid.getSQLState().startsWith("23"))
                                outcome = Outcome.FAILED;
                        }
                    }
                }
            }
        } catch (Exception | LinkageError | AssertionError unconfirmed) {
            outcome = Outcome.UNKNOWN;
        }
        close();
        return outcome;
    }

    /** Compares actual retained source against its pre-migration fingerprint. / 将实际保留来源与迁移前指纹比较。
     * @return whether original source is proven unchanged / 是否证明原来源未变
     * @throws Exception if source evidence is unavailable / 来源证据不可用时 */
    private boolean sourceMatches() throws Exception {
        if (review.plan().source().isEmpty())
            return true;
        var before = review.sourceDigest();
        return before.isPresent() && before.get().matches("[0-9a-f]{64}") && before.get()
                .equals(SchemaSnapshot.digest(database, new SchemaCoordinates(review.plan().source().get())));
    }

    /** Returns the observed outcome after a cleanup retry. / 清理重试后返回已观察结果。
     * @return original result / 原结果 */
    Outcome outcome() {
        return outcome;
    }

    /** Releases actual resources before recording the proven data state. / 在记录已证明数据状态之前释放实际资源。
     * @throws Exception if original physical or durable cleanup remains / 原物理或持久清理仍残留时 */
    @Override
    public void close() throws Exception {
        if (closed)
            return;
        if (reader != null)
            reader.close();
        loader.close();
        if (attempt != null) {
            if (outcome != Outcome.UNKNOWN && !sourceChecked) {
                if (!sourceMatches()) {
                    outcome = Outcome.UNKNOWN;
                    digest = null;
                }
                sourceChecked = true;
            }
            if (!cleanupDone) {
                ledger.cleanup(attempt, outcome != Outcome.UNKNOWN);
                cleanupDone = true;
            }
            ledger.finish(attempt, outcome, outcome == Outcome.SUCCEEDED ? digest : null);
        }
        closed = true;
    }
}
