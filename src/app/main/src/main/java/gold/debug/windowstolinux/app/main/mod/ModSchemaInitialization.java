package gold.debug.windowstolinux.app.main.mod;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.*;

import gold.debug.windowstolinux.app.db.PlatformDatabase;
import gold.debug.windowstolinux.app.db.mod.ModRestartLedger;
import gold.debug.windowstolinux.app.db.schema.*;
import gold.debug.windowstolinux.app.mod.loader.ModClassLoader;
import gold.debug.windowstolinux.appapi.db.*;
import gold.debug.windowstolinux.appapi.task.*;

/** Owns one isolated initialization attempt and retries cleanup without replaying author code. / 持有一次隔离初始化尝试，只重试清理而不重放作者代码。 */
final class ModSchemaInitialization implements AutoCloseable {
    /** Original physical engine. / 原物理引擎。 */
    private final PlatformDatabase database;

    /** Shared restart and preparation journal. / 共用重启和准备日志。 */
    private final ModRestartLedger ledger;

    /** Isolated author preparation loader. / 隔离作者准备加载器。 */
    private final ModClassLoader loader;

    /** Original durable preparation identities, absent before registration succeeds. / 原持久准备身份，登记成功前为空。 */
    private ModRestartLedger.Initialization plan;

    /** Original writer retained through physical release. / 保留至物理释放的原写入作用域。 */
    private SchemaAccessScope writer;

    /** Original reader retained through physical release. / 保留至物理释放的原读取作用域。 */
    private SchemaAccessScope reader;

    /** Observed result independent of remaining cleanup. / 独立于剩余清理的已观察结果。 */
    private Outcome outcome = Outcome.UNKNOWN;

    /** Successful bounded validation receipt. / 成功有界验证凭据。 */
    private String digest;

    /** Whether the only author execution was attempted. / 是否尝试过唯一作者执行。 */
    private boolean attempted;

    /** Whether resource release and its durable acknowledgement finished. / 资源释放及持久确认是否结束。 */
    private boolean closed;

    /** Retains the preparation loader before any persistent or author operation. / 在任何持久或作者操作前保留准备加载器。
     * @param database original engine / 原引擎
     * @param ledger original operation ledger / 原操作账本
     * @param loader separate preparation loader / 独立准备加载器 */
    ModSchemaInitialization(PlatformDatabase database, ModRestartLedger ledger, ModClassLoader loader) {
        this.database = database;
        this.ledger = ledger;
        this.loader = loader;
    }

    /** Executes first initialization and validation once before business construction. / 在业务构造前执行首次初始化及验证一次。
     * @param intent original selected request / 原已选请求
     * @param contentDigest verified code digest / 已验证代码摘要
     * @return exact ready generation / 精确就绪代次
     * @throws Exception if author validation, release or durable acknowledgement fails / 作者验证、释放或持久确认失败时 */
    SchemaIdentity initialize(UUID intent, String contentDigest) throws Exception {
        if (attempted)
            throw new IllegalStateException("Initialization cannot replay author code");
        attempted = true;
        try {
            plan = ledger.beginInitialization(intent, loader.manifest().json(), contentDigest);
            ledger.initializationPhase(plan, false);
            new SchemaProvisioner(database).create(plan.target());
            writer = new SchemaAccessScope(database, plan.target(), plan.writer(), false);
            ModSchemaLifecycle lifecycle;
            try {
                lifecycle = lifecycle();
                lifecycle.initialize(writer);
            } catch (Exception | LinkageError | AssertionError failure) {
                outcome = Outcome.FAILED;
                throw rejected();
            }
            try {
                writer.close();
            } catch (SQLException unclosed) {
                outcome = Outcome.FAILED;
                throw rejected();
            }
            ledger.initializationPhase(plan, true);
            reader = new SchemaAccessScope(database, plan.target(), plan.reader(), true);
            ValidationReport report;
            try {
                report = lifecycle.validate(reader);
                if (report == null || !report.valid() || report.businessDigest()
                        .map(v -> v.getBytes(StandardCharsets.UTF_8).length > 4096).orElse(false))
                    throw rejected();
            } catch (Exception | LinkageError | AssertionError failure) {
                outcome = Outcome.FAILED;
                throw rejected();
            }
            String receipt;
            try {
                receipt = SchemaValidation.validate(reader);
            } catch (SQLException failure) {
                if (failure.getSQLState() != null && failure.getSQLState().startsWith("23")) {
                    outcome = Outcome.FAILED;
                    throw rejected();
                }
                throw failure;
            }
            digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((receipt + "\n" + report.businessDigest().orElse("")).getBytes(StandardCharsets.UTF_8)));
            outcome = Outcome.SUCCEEDED;
        } catch (Exception | LinkageError | AssertionError failure) {
            try {
                close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        close();
        return plan.target();
    }

    /** Constructs only the fixed schema entry from its original main source. / 仅从原主来源构造固定 Schema 入口。
     * @return author schema lifecycle / 作者数据生命周期
     * @throws ReflectiveOperationException if origin, public shape or constructor is invalid / 来源、公开形状或构造器无效时 */
    private ModSchemaLifecycle lifecycle() throws ReflectiveOperationException {
        String root = loader.manifest().rootPackage();
        String name = root.substring(root.lastIndexOf('.') + 1);
        String className = root + ".db." + Character.toUpperCase(name.charAt(0)) + name.substring(1) + "Schema";
        var type = Class.forName(className, false, loader);
        int modifiers = type.getModifiers();
        if (!loader.ownsMainClass(type) || !Modifier.isPublic(modifiers) || Modifier.isAbstract(modifiers)
                || type.isInterface() || !ModSchemaLifecycle.class.isAssignableFrom(type))
            throw new ReflectiveOperationException("Main source must define a public concrete ModSchemaLifecycle");
        return ModSchemaLifecycle.class.cast(type.getConstructor().newInstance());
    }

    /** Returns a safe author rejection without storing author SQL, data or exception text. / 返回安全作者拒绝，不存储作者 SQL、数据或异常文本。
     * @return structured rejection / 结构化拒绝 */
    private ApiException rejected() {
        return new ApiException(new Problem(ProblemCode.UNAVAILABLE, "MOD_SCHEMA_INITIALIZATION_FAILED",
                "Initial data preparation failed; the original target is retained for review",
                Map.of("ownerId", loader.manifest().id()), Optional.empty()));
    }

    /** Reports whether cleanup left a known state that may receive a startup failure. / 报告清理是否留下可登记启动失败的已知状态。
     * @return whether the original attempt is durably closed with a known result / 原尝试是否已持久关闭且结果已知 */
    boolean acknowledged() {
        return closed && plan != null && outcome != Outcome.UNKNOWN;
    }

    /** Releases only original resources and retries only the original terminal transaction. / 仅释放原资源并仅重试原终态事务。
     * @throws Exception if a connection, loader or acknowledgement still remains / 连接、加载器或确认仍残留时 */
    @Override
    public void close() throws Exception {
        if (closed)
            return;
        if (writer != null)
            writer.close();
        if (reader != null)
            reader.close();
        loader.close();
        if (plan != null)
            ledger.finishInitialization(plan, outcome, digest);
        closed = true;
    }
}
