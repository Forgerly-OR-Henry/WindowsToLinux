package gold.debug.windowstolinux.app.main.start;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import javax.tools.ToolProvider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import gold.debug.windowstolinux.app.config.AppConfig;
import gold.debug.windowstolinux.app.main.runtime.RunModeResolver.*;
import gold.debug.windowstolinux.app.mod.config.ManifestReader;
import gold.debug.windowstolinux.app.mod.version.SemanticVersion;
import gold.debug.windowstolinux.app.service.mod.install.PlatformModInstallation;
import gold.debug.windowstolinux.app.service.mod.store.*;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreSelection.ReleaseKey;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import gold.debug.windowstolinux.appapi.mod.ModEntrypoint;

/** Real numeric-loopback store, compiled ordinary packages and isolated installed runtime. / 真实数字回环商店、编译普通包及隔离安装运行时。 */
public final class ModStoreFixture implements AutoCloseable {
    /** Known isolated fixture password. / 已知隔离夹具密码。 */
    public static final String PASSWORD = "isolated-store-password";

    /** Detects accidental author initialization during acquisition. / 检测获取期间意外作者初始化。 */
    public static final String AUTHOR_MARK = "wtl.store.author.executed";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path home;

    private final Path remote;

    private final HttpServer server;

    private final ExecutorService workers = Executors.newCachedThreadPool();

    private final Map<ReleaseKey, Package> packages = new ConcurrentHashMap<>();

    private final Map<ReleaseKey, Gate> held = new ConcurrentHashMap<>();

    private final Map<ReleaseKey, AtomicInteger> requests = new ConcurrentHashMap<>();

    private final Map<ReleaseKey, Integer> failures = new ConcurrentHashMap<>();

    private final Map<String, byte[]> jars = new HashMap<>();

    private final AtomicInteger catalogs = new AtomicInteger();

    private volatile Gate catalogGate = new Gate(false);

    private PlatformRuntime runtime;

    private PlatformModInstallation installer;

    private ModStoreService service;

    /** Opens actual HTTP and JAR-layout runtime ownership. / 打开实际 HTTP 及 JAR 布局运行时归属。
     * @param home isolated existing test root / 隔离现有测试根
     * @throws Exception if actual setup fails / 实际初始化失败时 */
    public ModStoreFixture(Path home) throws Exception {
        this.home = home.toAbsolutePath().normalize();
        remote = Files.createDirectories(this.home.resolve("http-packages"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext("/store/catalog.json", exchange -> route(exchange, this::catalog));
        server.createContext("/store/packages/", exchange -> route(exchange, this::packageRequest));
        server.start();
        try {
            open();
        } catch (Exception | Error failure) {
            if (runtime != null) try {
                runtime.close();
            } catch (Exception close) {
                failure.addSuppressed(close);
            }
            server.stop(0);
            workers.shutdown();
            try {
                if (!workers.awaitTermination(20, TimeUnit.SECONDS))
                    failure.addSuppressed(new IOException("HTTP fixture workers remain"));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure.addSuppressed(interrupted);
            }
            throw failure;
        }
    }

    private record Package(ModStoreGateway.Release release, Path file) {
    }

    private static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1), released;

        Gate(boolean hold) {
            released = new CountDownLatch(hold ? 1 : 0);
        }
    }

    interface Response {
        void run(HttpExchange exchange) throws Exception;
    }

    /** Returns the actual already-started platform. / 返回实际已启动平台。
     * @return actual runtime / 实际运行时 */
    public PlatformRuntime runtime() {
        return runtime;
    }

    /** Returns the actual seven-input store composition. / 返回实际七输入商店装配。
     * @return actual service / 实际服务 */
    public ModStoreService service() {
        return service;
    }

    /** Returns the isolated installed home. / 返回隔离安装根。
     * @return exact root / 精确根 */
    public Path home() {
        return home;
    }

    /** Returns numeric-loopback actual store root. / 返回数字回环实际商店根。
     * @return actual HTTP source / 实际 HTTP 来源 */
    public URI source() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/store/");
    }

    /** Creates another real service over original durable state. / 在原持久状态上创建另一个真实服务。
     * @return independent view/service lifetime / 独立视图及服务生命周期
     * @throws Exception if actual host API is unavailable / 实际宿主 API 不可用时 */
    public ModStoreService newService() throws Exception {
        return new ModStoreService(Optional.of(source()), new HttpModStoreGateway(Duration.ofSeconds(30)), Optional.of(installer),
            runtime.security(), runtime.persistence().database(), home, PlatformBuildIdentity.readApiVersion());
    }

    /** Builds and serves actual ordinary package bytes. / 构建并服务实际普通包字节。
     * @param owner exact Mod identity / 精确 Mod 身份
     * @param version exact version / 精确版本
     * @param required required author ranges / 必需作者范围
     * @param optional optional author ranges / 可选作者范围
     * @param purposes explicit directory purposes / 明确目录用途
     * @return exact actual release / 精确实际发布
     * @throws Exception if compilation or archive writing fails / 编译或归档写入失败时 */
    public ReleaseKey publish(String owner, String version, Map<String, String> required, Map<String, String> optional,
                              Map<String, String> purposes) throws Exception {
        return publish(owner, version, required, optional, purposes, 21, "[1.0.0-dev.1,2.0.0)");
    }

    /** Builds one actual package with explicit host bounds. / 构建一个具备明确宿主界限的实际包。
     * @param owner exact identity / 精确身份
     * @param version exact version / 精确版本
     * @param required required ranges / 必需范围
     * @param optional optional ranges / 可选范围
     * @param purposes declared purposes / 声明用途
     * @param javaVersion exact Java requirement / 精确 Java 要求
     * @param apiRange exact API range / 精确 API 范围
     * @return exact release / 精确发布
     * @throws Exception if actual fixture writing fails / 实际夹具写入失败时 */
    public synchronized ReleaseKey publish(String owner, String version, Map<String, String> required, Map<String, String> optional,
                                           Map<String, String> purposes, int javaVersion, String apiRange) throws Exception {
        var key = new ReleaseKey(owner, version);
        var manifest = manifest(owner, version, required, optional, javaVersion, apiRange);
        Path file = remote.resolve(owner + "-" + version + ".wtlmod");
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("mod.json", JSON.writeValueAsBytes(manifest));
        entries.put("README.md", "Actual store fixture".getBytes(StandardCharsets.UTF_8));
        entries.put("LICENSE", "MIT".getBytes(StandardCharsets.UTF_8));
        entries.put("main/fixture.jar", jar(owner));
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var entry : entries.entrySet()) {
                var member = new ZipEntry(entry.getKey());
                var crc = new CRC32();
                crc.update(entry.getValue());
                member.setMethod(ZipEntry.STORED);
                member.setSize(entry.getValue().length);
                member.setCrc(crc.getValue());
                zip.putNextEntry(member);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        var release = new ModStoreGateway.Release(ManifestReader.read(entries.get("mod.json")), URI.create("packages/" + file.getFileName()),
            Files.size(file), sha(file), purposes);
        packages.put(key, new Package(release, file));
        return key;
    }

    /** Changes only actual served directory declaration for mismatch counterexamples. / 仅改变实际服务目录声明以构造不一致反例。
     * @param key exact served release / 精确服务发布
     * @param required different directory-required ranges / 不同目录必需范围
     * @throws Exception if metadata is invalid / 元数据无效时 */
    public synchronized void differentDeclaration(ReleaseKey key, Map<String, String> required) throws Exception {
        differentDeclaration(key, required, Map.of(), 21, "[1.0.0-dev.1,2.0.0)");
    }

    /** Changes complete served metadata while preserving actual package bytes. / 保留实际包字节而改变完整服务元数据。
     * @param key exact release / 精确发布
     * @param required changed required ranges / 变化必需范围
     * @param optional changed optional ranges / 变化可选范围
     * @param javaVersion changed Java requirement / 变化 Java 要求
     * @param apiRange changed API range / 变化 API 范围
     * @throws Exception if declaration is invalid / 声明无效时 */
    public synchronized void differentDeclaration(ReleaseKey key, Map<String, String> required, Map<String, String> optional,
                                                  int javaVersion, String apiRange) throws Exception {
        var current = packages.get(key);
        var declared = ManifestReader.read(JSON.writeValueAsBytes(manifest(key.owner(), key.version(), required, optional, javaVersion, apiRange)));
        packages.put(key, new Package(new ModStoreGateway.Release(declared, current.release().packageUri(), current.release().bytes(),
            current.release().sha256(), current.release().purposes()), current.file()));
    }

    /** Serves a real incorrect declared digest for integrity counterexamples. / 服务真实错误声明摘要以构造完整性反例。
     * @param key exact release / 精确发布 */
    public void badDigest(ReleaseKey key) {
        var value = packages.get(key);
        packages.put(key, new Package(new ModStoreGateway.Release(value.release().manifest(), value.release().packageUri(),
            value.release().bytes(), "0".repeat(64), value.release().purposes()), value.file()));
    }

    /** Removes only a served catalog candidate, preserving actual installed files. / 仅移除服务目录候选，保留实际安装文件。
     * @param key exact catalog candidate / 精确目录候选 */
    public void remove(ReleaseKey key) {
        packages.remove(key);
    }

    /** Holds the actual package response after initial bytes. / 在初始字节后阻塞实际包响应。
     * @param key exact package / 精确包 */
    public void hold(ReleaseKey key) {
        held.put(key, new Gate(true));
    }

    /** Releases only the original held package response. / 仅放行原阻塞包响应。
     * @param key exact package / 精确包 */
    public void release(ReleaseKey key) {
        var gate = held.get(key);
        if (gate != null) gate.released.countDown();
    }

    /** Configures an actual HTTP package failure. / 配置实际 HTTP 包失败。
     * @param key exact package / 精确包
     * @param status real HTTP status, zero clears failure / 实际 HTTP 状态，零清除失败 */
    public void failure(ReleaseKey key, int status) {
        if (status == 0) failures.remove(key);
        else failures.put(key, status);
    }

    /** Returns actual package request count. / 返回实际包请求次数。
     * @param key exact package / 精确包
     * @return actual requests / 实际请求数 */
    public int requests(ReleaseKey key) {
        var count = requests.get(key);
        return count == null ? 0 : count.get();
    }

    /** Returns actual catalog request count. / 返回实际目录请求次数。
     * @return actual requests / 实际请求数 */
    public int catalogRequests() {
        return catalogs.get();
    }

    /** Waits for the real server to start the held package. / 等待真实服务器开始阻塞包。
     * @param key exact held package / 精确阻塞包
     * @throws Exception if the real request never starts / 实际请求未开始时 */
    public void awaitStarted(ReleaseKey key) throws Exception {
        if (!held.get(key).entered.await(20, TimeUnit.SECONDS))
            throw new AssertionError("Actual package request did not start");
    }

    /** Holds the actual catalog response body. / 阻塞实际目录响应 body。 */
    public void holdCatalog() {
        catalogGate = new Gate(true);
    }

    /** Releases the original catalog body. / 放行原目录 body。 */
    public void releaseCatalog() {
        catalogGate.released.countDown();
    }

    /** Waits for the real catalog request. / 等待真实目录请求。
     * @throws Exception if no actual request starts / 无实际请求开始时 */
    public void awaitCatalogStarted() throws Exception {
        if (!catalogGate.entered.await(20, TimeUnit.SECONDS))
            throw new AssertionError("Actual catalog request did not start");
    }

    /** Reopens actual durable platform state without modifying HTTP observations. / 重开实际持久平台状态，不修改 HTTP 观察。
     * @throws Exception if original runtime cannot close or reopen / 原运行时无法关闭或重开时 */
    public void reopen() throws Exception {
        runtime.close();
        open();
    }

    /** Executes isolated database traps against actual installation transactions. / 对实际安装事务执行隔离数据库陷阱。
     * @param sql original test statement / 原测试语句
     * @throws Exception if actual statement fails / 实际语句失败时 */
    public void sql(String sql) throws Exception {
        try (var connection = runtime.persistence().database().openConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** Counts actual durable facts. / 计数实际持久事实。
     * @param sql exact test query / 精确测试查询
     * @return actual count / 实际计数
     * @throws Exception if actual query fails / 实际查询失败时 */
    public long count(String sql) throws Exception {
        try (var connection = runtime.persistence().database().openConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new AssertionError("Actual count row missing");
            return rows.getLong(1);
        }
    }

    /** Reads actual non-secret registry values. / 读取实际非秘密登记值。
     * @param sql exact test query / 精确测试查询
     * @return actual value / 实际值
     * @throws Exception if actual query fails / 实际查询失败时 */
    public String text(String sql) throws Exception {
        try (var connection = runtime.persistence().database().openConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new AssertionError("Actual registry row missing");
            return rows.getString(1);
        }
    }

    private void open() throws Exception {
        var defaults = AppConfig.defaultsConfig();
        var config = new AppConfig("data", defaults.ui(), defaults.defaults(), defaults.ssh(), defaults.ai(), defaults.emergencyRecords(), true, Optional.of(source()));
        runtime = PlatformRuntime.open(new RuntimeLayout(RunMode.RUN_JAR, home, home.resolve("data")), FilePlatformFixture.PROTECTION, config);
        if (runtime.security().securityState().configured()) runtime.security().unlock(PASSWORD.toCharArray());
        else runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
        runtime.started();
        installer = runtime.modInstallation();
        service = newService();
    }

    private byte[] jar(String owner) throws Exception {
        if (jars.containsKey(owner)) return jars.get(owner);
        Path build = Files.createDirectories(home.resolve("compile-" + owner));
        Path source = build.resolve("Mod.java");
        Files.writeString(source, "package " + owner + "; import gold.debug.windowstolinux.appapi.mod.*; import gold.debug.windowstolinux.appapi.mod.lifecycle.*;"
            + "public class Mod implements ModEntrypoint { static { System.setProperty(\"" + AUTHOR_MARK + "\",\"initialized\"); }"
            + "public Mod(){} public void start(ModContext c){} public void stop(StopContext c){} }");
        Path api = Path.of(ModEntrypoint.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "--release", "21", "-classpath", api.toString(), "-d", build.toString(), source.toString());
        if (result != 0) throw new AssertionError("Actual fixture Mod compilation failed: " + result);
        var bytes = new ByteArrayOutputStream();
        try (var jar = new ZipOutputStream(bytes)) {
            jar.putNextEntry(new ZipEntry(owner.replace('.', '/') + "/Mod.class"));
            jar.write(Files.readAllBytes(build.resolve(owner.replace('.', '/')).resolve("Mod.class")));
            jar.closeEntry();
        }
        var actual = bytes.toByteArray();
        jars.put(owner, actual);
        return actual;
    }

    private static Map<String, Object> manifest(String owner, String version, Map<String, String> required,
                                                Map<String, String> optional, int javaVersion, String apiRange) {
        var dependencies = new ArrayList<Map<String, Object>>();
        new TreeMap<>(required).forEach((id, range) -> dependencies.add(Map.of("id", id, "versionRange", range, "optional", false)));
        new TreeMap<>(optional).forEach((id, range) -> dependencies.add(Map.of("id", id, "versionRange", range, "optional", true)));
        var node = new LinkedHashMap<String, Object>();
        node.put("formatVersion", 1);
        node.put("id", owner);
        node.put("name", owner);
        node.put("version", version);
        node.put("platformApiRange", apiRange);
        node.put("minJavaVersion", javaVersion);
        node.put("mainJar", "main/fixture.jar");
        node.put("entryClass", owner + ".Mod");
        node.put("description", "Actual controlled store fixture");
        node.put("authors", List.of("fixture"));
        node.put("dependencies", dependencies);
        node.put("exports", List.of());
        return node;
    }

    private void catalog(HttpExchange exchange) throws Exception {
        catalogs.incrementAndGet();
        var releases = new ArrayList<Map<String, Object>>();
        for (var value : packages.values().stream().sorted(Comparator.comparing(p -> p.release().owner() + "/" + p.release().version())).toList()) {
            var release = value.release();
            releases.add(Map.of("manifest", JSON.readTree(release.manifest().json()), "packageUri", release.packageUri().toString(),
                "bytes", release.bytes(), "sha256", release.sha256(), "purposes", release.purposes()));
        }
        send(exchange, JSON.writeValueAsBytes(Map.of("formatVersion", 1, "releases", releases)), catalogGate);
    }

    private void packageRequest(HttpExchange exchange) throws Exception {
        var value = packages.entrySet().stream().filter(entry -> exchange.getRequestURI().getPath().equals("/store/" + entry.getValue().release().packageUri())).findFirst();
        if (value.isEmpty()) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        var key = value.get().getKey();
        requests.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
        if (failures.containsKey(key)) {
            exchange.sendResponseHeaders(failures.get(key), -1);
            return;
        }
        send(exchange, Files.readAllBytes(value.get().getValue().file()), held.getOrDefault(key, new Gate(false)));
    }

    private static void send(HttpExchange exchange, byte[] bytes, Gate gate) throws Exception {
        exchange.sendResponseHeaders(200, bytes.length);
        int first = Math.min(32, bytes.length);
        exchange.getResponseBody().write(bytes, 0, first);
        exchange.getResponseBody().flush();
        gate.entered.countDown();
        if (!gate.released.await(20, TimeUnit.SECONDS)) throw new IOException("Original HTTP test hold timed out");
        exchange.getResponseBody().write(bytes, first, bytes.length - first);
    }

    private static void route(HttpExchange exchange, Response response) {
        try (exchange) {
            response.run(exchange);
        } catch (
            Exception expectedClientDisconnect) { /* Actual disconnect closes exchange. / 实际断开关闭 exchange。 */ }
    }

    private static String sha(Path file) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    /** Releases actual HTTP holds and waits for original accepted work before closing ownership. / 放行实际 HTTP 阻塞，等待原接纳工作后关闭归属。
     * @throws Exception if original work or actual cleanup remains / 原工作或实际清理仍残留时 */
    public void close() throws Exception {
        releaseCatalog();
        held.values().forEach(gate -> gate.released.countDown());
        Throwable failure = null;
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        try {
            while ((DesktopTaskRunner.hasActiveTasks() || runtime.security().securityState().activeTasks() != 0) && System.nanoTime() < deadline)
                Thread.sleep(5);
            if (DesktopTaskRunner.hasActiveTasks() || runtime.security().securityState().activeTasks() != 0)
                failure = new AssertionError("Original accepted store workers remain");
        } catch (InterruptedException wait) {
            failure = wait;
            interrupted = true;
        }
        try {
            runtime.close();
        } catch (Exception | Error cleanup) {
            if (failure == null) failure = cleanup;
            else failure.addSuppressed(cleanup);
        }
        try {
            server.stop(0);
        } catch (RuntimeException cleanup) {
            if (failure == null) failure = cleanup;
            else failure.addSuppressed(cleanup);
        }
        try {
            workers.shutdown();
            if (!workers.awaitTermination(20, TimeUnit.SECONDS))
                throw new IOException("Actual HTTP fixture workers remain");
        } catch (Exception cleanup) {
            if (cleanup instanceof InterruptedException) interrupted = true;
            if (failure == null) failure = cleanup;
            else failure.addSuppressed(cleanup);
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (failure instanceof Error error) throw error;
        if (failure instanceof Exception exception) throw exception;
    }
}
