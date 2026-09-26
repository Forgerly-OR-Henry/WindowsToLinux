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

/** Owns isolated initialization or migration and retries cleanup without replaying author code. / 持有隔离初始化或迁移，只重试清理而不重放作者代码。 */
final class ModSchemaPreparation implements AutoCloseable {
    /** Original physical engine. / 原物理引擎。 */
    private final PlatformDatabase database;

    /** Shared restart and preparation journal. / 共用重启和准备日志。 */
    private final ModRestartLedger ledger;

    /** Isolated author preparation loader. / 隔离作者准备加载器。 */
    private final ModClassLoader loader;

    /** Original durable preparation identities, absent before registration succeeds. / 原持久准备身份，登记成功前为空。 */
    private ModRestartLedger.Preparation plan;

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

    /** Original migration scopes, including earlier steps already closed. / 原迁移作用域，包括此前已关闭步骤。 */
    private final List<SchemaAccessScope> migrationScopes = new ArrayList<>();

    /** Intermediates whose creation returned successfully in this original attempt. / 本次原尝试中创建已成功返回的中间目标。 */
    private final List<SchemaCoordinates> intermediates = new ArrayList<>();

    /** Original source fingerprint persisted before author migration. / 作者迁移前持久化的原来源指纹。 */
    private String originalSource;

    /** Whether source preservation was durably checked after author execution. / 作者执行后是否已持久核对来源保全。 */
    private boolean sourceCompared;

    /** Original successful step proof awaiting only release and acknowledgement. / 仅等待释放和确认的原成功步骤证明。 */
    private StepCompletion pendingStep;

    /** Retains the preparation loader before any persistent or author operation. / 在任何持久或作者操作前保留准备加载器。
     * @param database original engine / 原引擎
     * @param ledger original operation ledger / 原操作账本
     * @param loader separate preparation loader / 独立准备加载器 */
    ModSchemaPreparation(PlatformDatabase database, ModRestartLedger ledger, ModClassLoader loader) {
        this.database = database;
        this.ledger = ledger;
        this.loader = loader;
    }

    /** Executes the original initialization or migration once before business construction. / 在业务构造前执行原初始化或迁移一次。
     * @param intent original selected request / 原已选请求
     * @param contentDigest verified code digest / 已验证代码摘要
     * @return exact ready generation / 精确就绪代次
     * @throws Exception if author validation, release or durable acknowledgement fails / 作者验证、释放或持久确认失败时 */
    SchemaIdentity prepare(UUID intent, String contentDigest) throws Exception {
        if (attempted)
            throw new IllegalStateException("Data preparation cannot replay author code");
        attempted = true;
        try {
            plan = ledger.beginPreparation(intent, loader.manifest().json(), contentDigest);
            if (plan.source().isPresent()) {
                migrate();
                close();
                if (outcome != Outcome.SUCCEEDED)
                    throw sourceChanged();
                return plan.target();
            }
            ledger.preparationPhase(plan, false);
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
            ledger.preparationPhase(plan, true);
            reader = new SchemaAccessScope(database, plan.target(), plan.reader(), true);
            digest = validate(lifecycle, reader);
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

    /** Executes the confirmed chain against isolated targets without invoking initialization. / 针对隔离目标执行已确认链，不调用初始化。
     * @throws Exception if execution, validation or release fails / 执行、验证或释放失败时 */
    private void migrate() throws Exception {
        var first = plan.steps().getFirst();
        var source = scope(ModRestartLedger.sourceCoordinates(plan, first), first.sourceReader(), true);
        originalSource = SchemaSnapshot.digest(database, new SchemaCoordinates(plan.source().orElseThrow()));
        ledger.sourceDigest(plan, originalSource, false);
        constraints(source);
        ledger.preparationPhase(plan, false);
        for (var step : plan.steps()) {
            if (step.index() != 0)
                source = scope(ModRestartLedger.sourceCoordinates(plan, step), step.sourceReader(), true);
            String before = SchemaSnapshot.digest(database, ModRestartLedger.sourceCoordinates(plan, step));
            ledger.beginStep(plan, step, before);
            var target = ModRestartLedger.targetCoordinates(plan, step);
            new SchemaProvisioner(database).create(target);
            boolean last = step.index() == plan.steps().size() - 1;
            if (!last)
                intermediates.add(target);
            writer = scope(target, step.writer(), false);
            try {
                var type = Class.forName(step.className(), false, loader);
                if (!Modifier.isPublic(type.getModifiers()) || Modifier.isAbstract(type.getModifiers())
                        || type.isInterface() || !SchemaMigration.class.isAssignableFrom(type))
                    throw rejected();
                var migration = SchemaMigration.class.cast(type.getConstructor().newInstance());
                migration.migrate(source, writer);
            } catch (Exception | LinkageError | AssertionError failure) {
                outcome = Outcome.FAILED;
                throw rejected();
            }
            try {
                writer.close();
                source.close();
            } catch (SQLException retained) {
                outcome = Outcome.FAILED;
                throw rejected();
            }
            String after = SchemaSnapshot.digest(database, ModRestartLedger.sourceCoordinates(plan, step));
            if (!before.equals(after))
                throw sourceChanged();
            reader = scope(target, step.reader(), true);
            String receipt;
            if (last) {
                ledger.preparationPhase(plan, true);
                try {
                    receipt = validate(lifecycle(), reader);
                } catch (ReflectiveOperationException | LinkageError | AssertionError failure) {
                    outcome = Outcome.FAILED;
                    throw rejected();
                }
                digest = receipt;
                outcome = Outcome.SUCCEEDED;
            } else {
                receipt = constraints(reader);
                outcome = Outcome.FAILED;
            }
            pendingStep = new StepCompletion(step, before, after, receipt);
            reader.close();
            completeStep();
            if (!last)
                outcome = Outcome.UNKNOWN;
        }
    }

    /** Allocates and retains one original intermediate or permanent scope. / 分配并保留一个原中间或正式作用域。
     * @param target registered target / 已登记目标
     * @param id original persisted scope / 原已持久作用域
     * @param readOnly whether only reads are granted / 是否仅授予读取
     * @return retained actual scope / 保留的实际作用域
     * @throws Exception if allocation fails / 分配失败时 */
    private SchemaAccessScope scope(SchemaCoordinates target, UUID id, boolean readOnly) throws Exception {
        var scope = new SchemaAccessScope(database, target, id, readOnly);
        migrationScopes.add(scope);
        return scope;
    }

    /** Classifies actual integrity failures without exposing private SQL details. / 对实际完整性失败分类，不暴露私有 SQL 详情。
     * @param access original restricted reader / 原受限读取作用域
     * @return structural validation receipt / 结构验证凭据
     * @throws Exception if constraints fail or cannot be checked / 约束失败或无法核验时 */
    private String constraints(SchemaAccessScope access) throws Exception {
        try {
            return SchemaValidation.validate(access);
        } catch (SQLException failure) {
            if (failure.getSQLState() != null && failure.getSQLState().startsWith("23")) {
                outcome = Outcome.FAILED;
                throw rejected();
            }
            throw failure;
        }
    }

    /** Retries only a completed callback's original step acknowledgement. / 仅重试已完成回调的原步骤确认。
     * @throws SQLException if the original acknowledgement remains unconfirmed / 原确认仍未确认时 */
    private void completeStep() throws SQLException {
        if (pendingStep != null) {
            ledger.completeStep(plan, pendingStep.step(), pendingStep.before(), pendingStep.after(),
                    pendingStep.digest());
            pendingStep = null;
        }
    }

    /** Reports changed source data as unknown instead of accepting or overwriting it. / 将已变化来源报告为未知，不接受或覆盖它。
     * @return safe original source diagnosis / 安全原来源诊断 */
    private ApiException sourceChanged() {
        outcome = Outcome.UNKNOWN;
        return new ApiException(new Problem(ProblemCode.UNKNOWN, "MOD_MIGRATION_SOURCE_CHANGED",
                "Original migration source no longer matches its recorded data; reconciliation is required",
                Map.of("ownerId", loader.manifest().id()), Optional.of(UUID.randomUUID())));
    }

    /** Combines author acceptance with actual read-only constraint checks. / 结合作者接受与实际只读约束检查。
     * @param lifecycle target author validator / 目标作者验证器
     * @param reader original target reader / 原目标读取作用域
     * @return bounded validation receipt / 有界验证凭据
     * @throws Exception if author or platform validation fails / 作者或平台验证失败时 */
    private String validate(ModSchemaLifecycle lifecycle, SchemaAccessScope reader) throws Exception {
        ValidationReport report;
        try {
            report = lifecycle.validate(reader);
            if (report == null || !report.valid()
                    || report.businessDigest().map(v -> v.getBytes(StandardCharsets.UTF_8).length > 4096).orElse(false))
                throw rejected();
        } catch (Exception | LinkageError | AssertionError failure) {
            outcome = Outcome.FAILED;
            throw rejected();
        }
        String receipt = constraints(reader);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((receipt + "\n" + report.businessDigest().orElse("")).getBytes(StandardCharsets.UTF_8)));
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
        return new ApiException(new Problem(ProblemCode.UNAVAILABLE,
                plan != null && plan.source().isPresent()
                        ? "MOD_SCHEMA_MIGRATION_FAILED"
                        : "MOD_SCHEMA_INITIALIZATION_FAILED",
                "Data preparation failed; the original source and final target are retained for review",
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
        for (var scope : migrationScopes)
            scope.close();
        if (writer != null)
            writer.close();
        if (reader != null)
            reader.close();
        loader.close();
        if (plan != null && plan.source().isPresent()) {
            if (outcome != Outcome.UNKNOWN) {
                completeStep();
                if (originalSource != null && !sourceCompared) {
                    String after = SchemaSnapshot.digest(database, new SchemaCoordinates(plan.source().get()));
                    boolean matches = ledger.sourceDigest(plan, after, true);
                    sourceCompared = true;
                    if (!matches)
                        outcome = Outcome.UNKNOWN;
                }
                if (outcome != Outcome.UNKNOWN)
                    for (var target : intermediates)
                        new SchemaProvisioner(database).dropIntermediate(target);
            }
        }
        if (plan != null)
            ledger.finishPreparation(plan, outcome, outcome == Outcome.SUCCEEDED ? digest : null);
        closed = true;
    }

    /** Original completed callback proof retained independently of cleanup. / 独立于清理保留的原已完成回调证明。
     * @param step original edge / 原迁移边
     * @param before original source fingerprint / 原来源指纹
     * @param after source fingerprint after migration / 迁移后来源指纹
     * @param digest validated target receipt / 已验证目标凭据 */
    private record StepCompletion(ModRestartLedger.MigrationStep step, String before, String after, String digest) {
    }
}
