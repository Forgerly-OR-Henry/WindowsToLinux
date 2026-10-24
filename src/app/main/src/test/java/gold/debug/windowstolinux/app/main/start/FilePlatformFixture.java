package gold.debug.windowstolinux.app.main.start;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.init.DatabaseProtection;
import gold.debug.windowstolinux.app.db.server.StoredServerIdentity;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflict;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflictChoice;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferProgress;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferStall;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferStallChoice;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.shell.ProcessShellFactory;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

/** Public test-only setup for genuine file capabilities without exporting unrelated Main tests. / 真实文件能力的公共测试初始化，不导出无关Main测试。 */
public final class FilePlatformFixture {
    /** Isolated registered Mod owner. / 隔离登记的Mod所有者。 */
    public static final String OWNER = "example.admission";

    /** Actual registered release. / 实际登记的发布版本。 */
    public static final String VERSION = "1.0.0";

    /** Reversible test storage protection, never production protection. / 可逆测试存储保护，不用于生产保护。 */
    public static final DatabaseProtection PROTECTION = new DatabaseProtection() {
        @Override
        public byte[] protect(byte[] input) {
            byte[] result = input.clone();
            for (int i = 0; i < result.length; i++) result[i] ^= 0x67;
            return result;
        }

        @Override
        public byte[] unprotect(byte[] input) {
            return protect(input);
        }
    };

    /** Prevents constructing a static test setup. / 不实例化静态测试初始化。 */
    private FilePlatformFixture() {
    }

    /** Creates the original production shell for independent author integration tests. / 为独立作者集成测试创建原生产外壳。
     * @param runtime original isolated runtime / 原隔离运行时
     * @param messages actual message catalog / 实际文案目录
     * @param appearance actual display settings / 实际显示设置
     * @param theme initial resolved theme / 初始已解析主题
     * @param reports bounded diagnostic writer / 有界诊断写入器
     * @param models host models resolved on the worker / 工作线程已解析的宿主模型
     * @return actual production window / 实际生产窗口
     * @throws Exception if original host composition fails / 原宿主装配失败时 */
    public static gold.debug.windowstolinux.app.ui.window.PlatformFrame window(PlatformRuntime runtime,
                                                                               gold.debug.windowstolinux.app.ui.language.MessageCatalog messages,
                                                                               gold.debug.windowstolinux.app.ui.display.DesktopDisplayConfig appearance,
                                                                               gold.debug.windowstolinux.app.ui.display.ThemeMode theme,
                                                                               gold.debug.windowstolinux.app.ui.error.FailureReportStore reports,
                                                                               gold.debug.windowstolinux.app.service.ai.PlatformModelService models) throws Exception {
        return ApplicationLauncher.createWindow(runtime, messages, appearance, theme, reports, models);
    }

    /** Registers the genuine unlocked no-Schema activation using the current ledger contract. / 按当前账本合同登记真实已解锁无Schema启用。
     * @param runtime actual isolated platform runtime / 实际隔离平台运行时
     * @throws Exception if actual credentials or registry setup fails / 实际凭据或登记初始化失败时 */
    public static void initialize(PlatformRuntime runtime) throws Exception {
        var password = "fixture-password".toCharArray();
        try {
            runtime.security().setupAndUnlock(password, password);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
        try (var operation = runtime.security().enterOperation(); var credentials = runtime.security().openCredentials()) {
            credentials.save("credential", "fixture-secret".toCharArray());
        }
        runtime.persistence().servers().saveServerProfile(new StoredServerProfile("server", "host.example", 22, "alice", "credential", "Server"));
        var db = runtime.persistence().database();
        String manifest = """
            {"formatVersion":1,"id":"example.admission","name":"Fixture","version":"1.0.0",\
            "platformApiRange":"1.0.0","minJavaVersion":21,"mainJar":"main/fixture.jar",\
            "entryClass":"example.admission.Mod","dependencies":[]}
            """;
        sql(db, "INSERT INTO mod_registry(mod_id,updated_at) VALUES(?,1)", OWNER);
        sql(db, "INSERT INTO mod_version(mod_id,version,install_path,content_sha256,schema_version,manifest_json,origin_kind,installed_at,state) VALUES(?,?,?, ?,?,?,'CLASS',1,'READY')",
            OWNER, VERSION, "fixture-only", "0".repeat(64), null, manifest);
        sql(db, "UPDATE mod_registry SET enabled=TRUE,selected_version=?,selection_initialized=TRUE,actual_version=?,last_runtime_state='RUNNING',last_boot_id=? WHERE mod_id=?",
            VERSION, VERSION, db.bootId(), OWNER);
    }

    /** Executes only explicit isolated setup SQL. / 仅执行明确的隔离初始化SQL。
     * @param db actual platform database / 实际平台数据库
     * @param statement current setup statement / 当前初始化语句
     * @param values bound values / 绑定值
     * @throws SQLException if setup persistence fails / 初始化持久化失败时 */
    private static void sql(PlatformDatabase db, String statement, Object... values) throws SQLException {
        try (var connection = db.openConnection(); var query = connection.prepareStatement(statement)) {
            for (int i = 0; i < values.length; i++) query.setObject(i + 1, values[i]);
            query.executeUpdate();
        }
    }

    /** Reads one actual isolated ledger count. / 读取一个实际隔离账本计数。
     * @param db actual platform database / 实际平台数据库
     * @param query explicit count query / 明确计数查询
     * @return actual count / 实际计数
     * @throws SQLException if evidence cannot be read / 无法读取依据时 */
    public static long count(PlatformDatabase db, String query) throws SQLException {
        try (var connection = db.openConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery(query)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    /** Checks the original callback has physically returned within the existing deadline. / 在既有期限内检查原回调已实际返回。
     * @param runtime original platform runtime / 原平台运行时
     * @param task authentic original callback context / 真实原回调上下文
     * @throws Exception if callback remains running or evidence differs / 回调仍运行或依据不符时 */
    public static void callbackReturned(PlatformRuntime runtime, TaskContext task) throws Exception {
        assertNotNull(task);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            try {
                runtime.tasks().requireCleanup(task, OWNER, VERSION);
            } catch (ApiException rejected) {
                assertEquals(ProblemCode.UNAVAILABLE, rejected.problem().code());
                assertEquals("TASK_NOT_EXECUTING", rejected.problem().eventCode());
                return;
            }
            Thread.sleep(5);
        }
        fail("The original callback did not return after its failure");
    }

    /** Test human interaction seam; every file action still uses real platform admission. / 测试人工交互接缝；每个文件动作仍走真实平台准入。 */
    public static class Prompts implements PlatformInteractionService.Prompts {
        /** Leaves an actual transfer collision untouched. / 保持实际传输冲突对象不变。
         * @param conflict actual conflict / 实际冲突
         * @param server original server / 原服务器
         * @param task original task / 原任务
         * @param cancellation original cancellation / 原取消
         * @return explicit skip choice / 明确跳过选择 */
        @Override
        public TransferConflictChoice transferConflict(TransferConflict conflict, ServerId server, TaskSnapshot task, CancelToken cancellation) {
            return TransferConflictChoice.SKIP;
        }

        /** Terminates a stalled real transfer. / 终止已停滞的真实传输。
         * @param stall actual stall / 实际停滞
         * @param server original server / 原服务器
         * @param task original task / 原任务
         * @param cancellation original cancellation / 原取消
         * @return explicit termination / 明确终止 */
        @Override
        public TransferStallChoice transferStalled(TransferStall stall, ServerId server, TaskSnapshot task, CancelToken cancellation) {
            return TransferStallChoice.TERMINATE;
        }

        /** Reports the real transfer stage. / 报告真实传输阶段。
         * @param progress actual progress / 实际进展
         * @return actual stage name / 实际阶段名 */
        @Override
        public String transferProgress(TransferProgress progress) {
            return progress.stage().name();
        }

        /** Supplies an explicit test human approval. / 提供明确测试人工审批。
         * @param intent actual intent / 实际意图
         * @param task original task / 原任务
         * @param cancellation original cancellation / 原取消
         * @return explicit approval / 明确同意 */
        @Override
        public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken cancellation) {
            return true;
        }

        /** Dismisses unused test text requests. / 关闭未使用的测试文本请求。
         * @param title actual title / 实际标题
         * @param prompt actual prompt / 实际提示
         * @param secret whether masked / 是否遮蔽
         * @param task original task / 原任务
         * @param cancellation original cancellation / 原取消
         * @return dismissed input / 已关闭输入 */
        @Override
        public char[] text(String title, String prompt, boolean secret, TaskSnapshot task, CancelToken cancellation) {
            return null;
        }
    }

    /** Owns an actual isolated SSH/SFTP listener and physical files. / 持有实际隔离SSH/SFTP监听器及物理文件。 */
    public static final class Remote implements AutoCloseable {
        /** Actual test-owned listener. / 实际测试所属监听器。 */
        public final LoopbackSshServer listener;

        /** Actual physical storage behind SFTP. / SFTP后的实际物理存储。 */
        public final Path files;

        /** Starts the real loopback protocol fixture with the existing managed Bash seam. / 使用既有受管Bash接缝启动真实回环协议夹具。
         * @param root isolated listener root / 隔离监听器根
         * @throws Exception if genuine setup fails / 真实初始化失败时 */
        public Remote(Path root) throws Exception {
            Files.createDirectories(root);
            files = Files.createDirectories(root.resolve("remote"));
            var bash = Path.of(System.getProperty("managed.test.bash", "/bin/bash"));
            assertTrue(Files.isRegularFile(bash), "Local integration requires managed.test.bash");
            listener = new LoopbackSshServer(root, server -> {
                server.setFileSystemFactory(new VirtualFileSystemFactory(files));
                server.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build()));
                server.setPasswordAuthenticator((user, password, session) -> user.equals("alice") && password.equals("fixture-secret"));
                server.setCommandFactory((channel, command) -> {
                    Path entry = root.resolve("wire-" + UUID.randomUUID() + ".sh");
                    Files.writeString(entry, command, java.nio.charset.StandardCharsets.UTF_8);
                    return new ProcessShellFactory("fixture", List.of(bash.toString(), "--noprofile", "--norc", entry.toString())) {
                        @Override
                        protected List<String> resolveEffectiveCommand(ChannelSession ignored, String name, List<String> arguments) {
                            return arguments;
                        }
                    }.createShell(channel);
                });
            });
        }

        /** Registers the real loopback profile and pins its actual generated SSH wire key. / 登记真实回环配置并固定实际生成的SSH编码密钥。
         * @param runtime actual isolated platform runtime / 实际隔离平台运行时
         * @throws Exception if registry or actual key evidence fails / 登记或实际密钥依据失败时 */
        public void configure(PlatformRuntime runtime) throws Exception {
            initialize(runtime);
            int port = listener.server().getPort();
            runtime.persistence().servers().saveServerProfile(new StoredServerProfile("server", "127.0.0.1", port, "alice", "credential", "本机回环"));
            var key = listener.server().getKeyPairProvider().loadKeys(null).iterator().next().getPublic();
            runtime.persistence().servers().saveServer(new StoredServerIdentity("server", "127.0.0.1", port, KeyUtils.getFingerPrint(BuiltinDigests.sha256, key)));
        }

        /** Closes and verifies the actual listener and physical worker ownership. / 关闭并核验实际监听器及物理线程归属。
         * @throws Exception if genuine shutdown remains incomplete / 真实关闭仍未完成时 */
        @Override
        public void close() throws Exception {
            listener.close();
        }
    }
}
