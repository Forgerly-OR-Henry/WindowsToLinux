package gold.debug.windowstolinux.mod.deployment.linux.command;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.lang.reflect.Proxy;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.run.*;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelCommandIdentity;
import gold.debug.windowstolinux.mod.deployment.linux.mirror.DeploymentMirrorRuntime;
import gold.debug.windowstolinux.mod.deployment.service.mirror.*;

/** Local wire/transport proof; the Windows interpreter/path bridge is not a Linux privilege test. */
class DeploymentMirrorHostCommandTest {
    private static final ServerId SERVER = new ServerId("mirror-host-fixture");
    private static final String ORIGIN = "https://origin.invalid/files/";
    private final UUID id = UUID.randomUUID();
    private Path directory;
    private final TaskContext task = (TaskContext)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{TaskContext.class},
        (proxy, method, args) -> switch (method.getName()) {
            case "id" -> id;
            case "cancelToken" -> (CancelToken)() -> false;
            default -> throw new AssertionError(method.getName());
        });

    @BeforeEach void directory() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve(".ai-workspace"))) root = root.getParent();
        assertNotNull(root);
        directory = Files.createDirectories(root.resolve(".ai-workspace/tmp/agent-mirror-guidance-20261104/parent/" + id));
    }

    @AfterEach void cleanup() throws Exception {
        DeploymentMirrorTasks.forget(task);
        if (directory != null) try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    @SuppressWarnings("unchecked") private void bind(String policy) throws Exception {
        var active = DeploymentMirrorTasks.class.getDeclaredField("ACTIVE"); active.setAccessible(true);
        ((Map<UUID,String>)active.get(null)).put(id, policy);
        var layouts = DeploymentMirrorTasks.class.getDeclaredField("LAYOUTS"); layouts.setAccessible(true);
        ((Map<UUID,DeploymentRemoteLayout>)layouts.get(null)).put(id, DeploymentRemoteLayout.original());
    }

    private String policy(int port, boolean single, boolean explicit) {
        var points = new EnumMap<DownloadPoint,List<ResolvedMirrorPolicy.Endpoint>>(DownloadPoint.class);
        for (var point : DownloadPoint.values()) points.put(point, List.of());
        var endpoints = new ArrayList<ResolvedMirrorPolicy.Endpoint>();
        for (String source : single ? List.of("first") : List.of("first", "second")) {
            String base = "http://127.0.0.1:" + port + "/" + source + "/";
            endpoints.add(new ResolvedMirrorPolicy.Endpoint(source, "HTTP_ARCHIVE", base, List.of("127.0.0.1"), false, explicit,
                List.of(ORIGIN), List.of(new ResolvedMirrorPolicy.Route(ORIGIN, base)), Map.of(), "", explicit));
        }
        points.put(DownloadPoint.NODE_DISTRIBUTION, endpoints);
        return new ResolvedMirrorPolicy(1,id.toString(),SERVER.id(),"fixture-v1","CN","cn",
            Map.of("connectSeconds",2,"noProgressSeconds",2,"readSeconds",2,"lowSpeedWindowSeconds",3,
                "lowSpeedBytesPerSecond",1,"attemptsPerEndpoint",2),points).toJson();
    }

    @Test void canonicalHostInputRetainsAuthoredScriptAndRejectsTamperingBeforeReview() throws Exception {
        bind(policy(18181,false,true));
        String authored = "pwd\n";
        assertDoesNotThrow(() -> DeploymentMirrorTasks.requireProjectScript(task));
        String body = DeploymentMirrorTasks.hostCommandBody(authored,task,SERVER,20);
        String envelope = DeploymentModelCommandIdentity.envelope(id,UUID.randomUUID(),sha(body),body,"Local inspection");
        var facts = DeploymentModelCommandIdentity.executableFacts(envelope);
        assertEquals(authored,facts.get("modelScript")); assertEquals(body,facts.get("executionBody"));
        assertEquals(sha(body),facts.get("bodySha256")); assertEquals(sha(authored),facts.get("modelScriptSha256"));
        assertEquals(20L,facts.get("mirrorBudgetSeconds"));
        assertTrue(envelope.indexOf(DeploymentModelCommandIdentity.ENVIRONMENT + " /bin/bash --noprofile --norc -se")
            < envelope.indexOf("# WTL_MIRROR_TASK="));
        assertThrows(IllegalArgumentException.class, () -> DeploymentModelCommandIdentity.executableFacts(envelope.replace("wtl_mirror_connect_seconds=2", "wtl_mirror_connect_seconds=9")));
        assertThrows(IllegalArgumentException.class, () -> DeploymentMirrorTasks.hostCommandBody(authored,task,new ServerId("other"),20));
        assertThrows(IllegalArgumentException.class, () -> DeploymentMirrorTasks.hostCommandBody("x".repeat(24001),task,SERVER,20));
        assertThrows(IllegalArgumentException.class, () -> DeploymentModelCommandIdentity.executableFacts(
            DeploymentModelCommandIdentity.envelope(UUID.randomUUID(),UUID.randomUUID(),sha(body),body,"Foreign task")));
        DeploymentMirrorTasks.forget(task);
        assertEquals(authored,DeploymentMirrorTasks.hostCommandBody(authored,task,SERVER,20));
        assertEquals(authored,DeploymentModelCommandIdentity.executableFacts(
            DeploymentModelCommandIdentity.envelope(id,UUID.randomUUID(),sha(authored),authored,"Legacy body")).get("modelScript"));
    }

    @Test void projectFixedChainAndHostAutomaticChainUseRealSelectedRequestsAfterEnvironmentReset() throws Exception {
        transfer(false,false,404,false,false);
        transfer(true,false,503,true,false);
    }

    @Test void aSingleFixedSourceDoesNotRequestTheUnconfiguredSecondSource() throws Exception {
        transfer(true,true,404,false,false);
    }

    @Test void expectedArtifactIdentitySurvivesDigestFailureAndFixedSourceFailure() throws Exception {
        transfer(true,false,200,false,true);
        transfer(true,true,200,false,true);
    }

    private void transfer(boolean host, boolean single, int firstStatus, boolean automatic, boolean corrupt) throws Exception {
        byte[] expected = "same immutable artifact\n".getBytes(StandardCharsets.UTF_8);
        var first = new AtomicInteger(); var second = new AtomicInteger();
        Path output = directory.resolve(UUID.randomUUID() + ".artifact");
        Path lock = directory.resolve("retained.lock"); Files.writeString(lock,"version=1; original URL and digest\n");
        String before = Files.readString(lock);
        try (var server = new Repository(expected,firstStatus,corrupt,first,second)) {
            String policy = policy(server.port(),single,!automatic); bind(policy);
            String authored = "[ \"${UNRELATED_TEST_SECRET-unset}\" = unset ]\nwtl_download NODE_DISTRIBUTION "
                + quote(ORIGIN + "artifact") + " " + quote(output.toString().replace('\\','/'))
                + " --algorithm sha256 --expected " + sha(expected) + "\n";
            String body = host ? DeploymentMirrorTasks.hostCommandBody(authored,task,SERVER,20)
                : DeploymentMirrorRuntime.wrapBuild(authored,policy,20);
            if (host) {
                String envelope = DeploymentModelCommandIdentity.envelope(id,UUID.randomUUID(),sha(body),body,"Controlled artifact download");
                assertEquals(body,DeploymentModelCommandIdentity.executableFacts(envelope).get("executionBody"));
            }
            Result result = runScrubbed(body);
            boolean success = !single;
            assertEquals(success ? 0 : 65,result.exit(),result.output() + "\nRequests: " + server.requests);
            assertTrue(first.get() >= 1); assertTrue(first.get() <= 2);
            if (success) assertEquals(1,second.get(),result.output());
            else assertEquals(0,second.get(),result.output());
            assertEquals(success,Files.exists(output));
            if (success) assertArrayEquals(expected,Files.readAllBytes(output));
            else if (corrupt) assertTrue(result.output().contains("digest") || result.output().contains("checksum"),result.output());
            assertEquals(before,Files.readString(lock));
        }
    }

    /** Finite HTTP/1.0 responses with an explicit FIN before closing the accepted socket. */
    private static final class Repository implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket();
        private final ExecutorService workers = Executors.newCachedThreadPool();
        private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());

        Repository(byte[] expected, int firstStatus, boolean corrupt, AtomicInteger first, AtomicInteger second) throws IOException {
            listener.bind(new InetSocketAddress("127.0.0.1",0));
            workers.submit(() -> {
                while (!listener.isClosed()) try {
                    Socket client = listener.accept();
                    clients.add(client);
                    workers.submit(() -> {
                        try (client) {
                            client.setSoTimeout(2000);
                            var input = new BufferedReader(new InputStreamReader(client.getInputStream(),StandardCharsets.US_ASCII));
                            String request = input.readLine();
                            if (request == null) return;
                            requests.add(request);
                            for (int i = 0; i < 100; i++) {
                                String header = input.readLine();
                                if (header == null || header.isEmpty()) break;
                                if (i == 99) throw new IOException("Too many fixture request headers");
                            }
                            boolean primary = request.startsWith("GET /first/");
                            (primary ? first : second).incrementAndGet();
                            byte[] bytes = primary && corrupt ? "wrong artifact".getBytes(StandardCharsets.UTF_8) : expected;
                            int status = primary ? firstStatus : 200;
                            var output = client.getOutputStream();
                            output.write(("HTTP/1.0 " + status + " Fixture\r\nContent-Length: " + bytes.length
                                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                            output.write(bytes); output.flush(); client.shutdownOutput();
                            try { while (input.read() != -1) { } }
                            catch (SocketTimeoutException awaitingPeerClose) { }
                        } catch (IOException failure) {
                            if (!listener.isClosed()) requests.add("SERVER_ERROR " + failure.getClass().getSimpleName());
                        } finally { clients.remove(client); }
                    });
                } catch (IOException failure) {
                    if (!listener.isClosed()) requests.add("ACCEPT_ERROR " + failure.getClass().getSimpleName());
                }
            });
        }

        int port() { return listener.getLocalPort(); }

        @Override public void close() throws Exception {
            listener.close();
            for (Socket client : clients) client.close();
            workers.shutdown();
            assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS),"Fixture HTTP workers did not exit");
        }
    }

    private record Result(int exit,String output) { }
    private Result runScrubbed(String body) throws Exception {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        String bash = System.getProperty("managed.test.bash", windows ? "E:/Program/Git/bin/bash.exe" : "/bin/bash");
        String preamble = "";
        if (windows) {
            String python = System.getProperty("managed.test.python","E:/Program/Python/Python3.12/python.exe");
            // Only the OS path/interpreter edge is replaced. The frozen policy, transfer code and HTTP requests run unchanged.
            preamble = "fixture_python() {\n local -a args=(); local item\n for item in \"$@\"; do case \"$item\" in /*) args+=(\"$(/usr/bin/cygpath -w \"$item\")\");; *) args+=(\"$item\");; esac; done\n"
                + " WTL_MIRROR_POLICY=\"$(/usr/bin/cygpath -w \"$WTL_MIRROR_POLICY\")\" " + quote(python) + " \"${args[@]}\"\n}\n";
            body = body.replace("wtl_download() { /usr/bin/python3 -I ","wtl_download() { fixture_python -I ");
        }
        Path child = directory.resolve(UUID.randomUUID() + "-child.sh");
        Files.writeString(child,"set -eu\n" + preamble + body);
        Path launcher = directory.resolve(UUID.randomUUID() + "-launcher.sh");
        String path = shellPath(directory);
        Files.writeString(launcher,"exec " + DeploymentModelCommandIdentity.ENVIRONMENT + " TEMP=" + quote(path) + " TMP=" + quote(path)
            + " TMPDIR=" + quote(path) + " /bin/bash --noprofile --norc " + quote(shellPath(child)) + "\n");
        Path log = directory.resolve(UUID.randomUUID() + ".log");
        var builder = new ProcessBuilder(bash,"--noprofile","--norc",launcher.toString()).directory(directory.toFile())
            .redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("TEMP",directory.toString()); builder.environment().put("TMP",directory.toString());
        builder.environment().put("UNRELATED_TEST_SECRET","must-not-survive-env-i");
        var process = builder.start();
        try { assertTrue(process.waitFor(30,TimeUnit.SECONDS),"Mirror body process did not exit");
            return new Result(process.exitValue(),Files.readString(log));
        } finally { if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(5,TimeUnit.SECONDS)); } }
    }

    private static String shellPath(Path value) {
        String path = value.toAbsolutePath().toString().replace('\\','/');
        return path.length() > 2 && path.charAt(1) == ':' ? "/" + Character.toLowerCase(path.charAt(0)) + path.substring(2) : path;
    }
    private static String quote(String text) { return "'" + text.replace("'","'\"'\"'") + "'"; }
    private static String sha(String text) throws Exception { return sha(text.getBytes(StandardCharsets.UTF_8)); }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
