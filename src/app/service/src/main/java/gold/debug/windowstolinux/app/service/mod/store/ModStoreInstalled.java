package gold.debug.windowstolinux.app.service.mod.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.service.mod.PlatformModPrograms;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreCatalog.*;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreSelection.ReleaseKey;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;

/** Reads actual registered installed programs without author startup or source discovery. / 读取实际登记安装程序，不启动作者或发现源码。 */
final class ModStoreInstalled {
    /** Original platform database. / 原平台数据库。 */
    private final PlatformDatabase database;

    /** Exact installed program verifier. / 精确安装程序核验器。 */
    private final PlatformModPrograms programs;

    /** Captures only the actual installed layout. / 仅捕获实际安装布局。
     * @param database original platform database / 原平台数据库
     * @param home exact application home / 精确应用根 */
    ModStoreInstalled(PlatformDatabase database, Path home) {
        this.database = database;
        programs = new PlatformModPrograms(database, home, false);
    }

    /** Complete verified snapshot plus rejected registrations. / 完整已核验快照及拒绝登记。
     * @param installed actual verified programs / 实际已核验程序
     * @param issues actual failed registrations / 实际失败登记 */
    record Snapshot(List<Installed> installed, List<InstalledIssue> issues) {
    }

    /** Enumerates registration identities, then verifies every actual program. / 枚举登记身份，再核验每个实际程序。
     * @param cancellation original request / 原请求
     * @return actual verified observations / 实际核验观察
     * @throws Exception if inventory cannot be read / 无法读取清单时 */
    Snapshot read(CancelToken cancellation) throws Exception {
        var keys = new ArrayList<ReleaseKey>();
        try (var c = database.openConnection(); var statement = c.createStatement();
             var rows = statement.executeQuery("SELECT mod_id,version FROM mod_version WHERE state='READY' AND origin_kind IN ('LOCAL','BUNDLED','STORE') ORDER BY mod_id,version")) {
            while (rows.next()) {
                ModStoreFacts.cancellation(cancellation);
                keys.add(new ReleaseKey(rows.getString(1), rows.getString(2)));
            }
        }
        var installed = new ArrayList<Installed>();
        var issues = new ArrayList<InstalledIssue>();
        for (var key : keys) {
            ModStoreFacts.cancellation(cancellation);
            try {
                var program = programs.read(key.owner(), key.version());
                installed.add(facts(program));
            } catch (IOException invalid) {
                issues.add(new InstalledIssue(key, ModStoreFacts.problem(ProblemCode.CONTRACT_VIOLATION,
                    "MOD_STORE_INSTALLED_INVALID", invalid.getMessage())));
            }
        }
        return new Snapshot(List.copyOf(installed), List.copyOf(issues));
    }

    /** Rechecks exact actual program bytes against original proposal facts. / 按原计划事实复核精确实际程序字节。
     * @param expected original verified facts / 原已核验事实
     * @return actual currently verified facts / 实际当前核验事实
     * @throws Exception if declaration or bytes changed / 声明或字节变化时 */
    Installed verify(Installed expected) throws Exception {
        var program = programs.read(expected.manifest().id(), expected.manifest().version().toString());
        if (!program.contentDigest().equals(expected.contentSha256()) || !ModStoreFacts.same(program.manifest(), expected.manifest()))
            throw new IOException("Installed program changed after plan");
        return facts(program);
    }

    /** Reads readiness after actual installer completion. / 实际安装器完成后读取就绪事实。
     * @param key exact identity / 精确身份
     * @return actual program / 实际程序
     * @throws Exception if READY or physical evidence is unavailable / READY 或物理依据不可用时 */
    Installed read(ReleaseKey key) throws Exception {
        var program = programs.read(key.owner(), key.version());
        return facts(program);
    }

    /** Reads actual enabled and selected facts, without inferring runtime. / 读取实际启用及选择事实，不推测运行。
     * @param program actual verified program / 实际核验程序
     * @return actual release observation / 实际发布观察
     * @throws Exception if registry ownership disappeared / 登记归属消失时 */
    private Installed facts(PlatformModPrograms.Program program) throws Exception {
        try (var connection = database.openConnection(); var query = connection.prepareStatement(
            "SELECT enabled,selected_version FROM mod_registry WHERE mod_id=?")) {
            query.setString(1, program.manifest().id());
            try (var row = query.executeQuery()) {
                if (!row.next()) throw new IOException("Installed owner registration disappeared");
                return new Installed(program.manifest(), program.contentDigest(), row.getBoolean(1),
                    program.manifest().version().toString().equals(row.getString(2)));
            }
        }
    }
}
