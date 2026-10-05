package gold.debug.windowstolinux.mod.files.service;

import static gold.debug.windowstolinux.app.main.start.FilePlatformFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import gold.debug.windowstolinux.app.main.mod.PlatformModHost;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.main.start.FilePlatformFixture;
import gold.debug.windowstolinux.app.service.file.PlatformFileAccess;
import gold.debug.windowstolinux.app.service.file.PlatformFileService;
import gold.debug.windowstolinux.app.service.log.PlatformLogService;
import gold.debug.windowstolinux.app.service.server.PlatformServerAccess;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.mod.lifecycle.ModLaunchMode;
import gold.debug.windowstolinux.appapi.mod.model.ModIdentity;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.recover.TaskRecovery;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.task.run.TaskWork;
import gold.debug.windowstolinux.mod.files.file.model.FilesObservation;
import gold.debug.windowstolinux.mod.files.service.FilesRecovery;
import gold.debug.windowstolinux.mod.files.service.FilesService;

/** Test-only composition of genuine platform capabilities and physical SSH/SFTP. / 仅测试使用的真实平台能力及物理SSH/SFTP组合。 */
public final class FilesRuntimeFixture implements AutoCloseable {
    private final Path root;

    private final Path local;

    private final PlatformRuntime runtime;

    private final FilePlatformFixture.Remote remote;

    private final PlatformModHost.Remote capabilities;

    private final PlatformFileAccess source;

    private final PlatformServerAccess servers;

    private final PlatformLogService.Binding logs;

    private final TaskService tasks;

    private final FilesService service;

    private final List<AutoCloseable> registrations = new ArrayList<>();

    private final Map<UUID, TaskContext> contexts = new ConcurrentHashMap<>();

    private volatile Path chosen;

    private volatile BiFunction<TaskSnapshot, CancelToken, Path> chooser = (task, cancel) -> chosen;

    private volatile int conflict = 2;

    private volatile BiFunction<ActionIntent, TaskSnapshot, Boolean> approval = (intent, task) -> true;

    /** Creates actual isolated platform storage and optional protocol server. / 创建实际隔离平台存储及可选协议服务器。
     * @param root isolated test root / 隔离测试根
     * @param withRemote whether to start actual SSH/SFTP / 是否启动实际SSH/SFTP
     * @throws Exception if actual setup fails / 实际初始化失败时 */
    public FilesRuntimeFixture(Path root, boolean withRemote) throws Exception {
        this.root = root.toAbsolutePath().normalize();
        local = Files.createDirectories(root.resolve("local"));
        chosen = local;
        var opened = new ArrayList<AutoCloseable>();
        try {
            remote = withRemote ? new FilePlatformFixture.Remote(root.resolve("ssh")) : null;
            if (remote != null) opened.add(remote);
            runtime = PlatformRuntime.open(root.resolve("db"), PROTECTION);
            opened.add(runtime);
            if (remote == null) initialize(runtime);
            else remote.configure(runtime);
            runtime.fileSettings().save(false);
            runtime.setSourcePicker((title, task, cancel) -> chooser.apply(task, cancel));
            runtime.setInteractionPresenter(new FilePlatformFixture.Prompts() {
                @Override
                public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken cancel) {
                    return approval.apply(intent, task);
                }

                @Override
                public int fileConflict(gold.debug.windowstolinux.appapi.file.model.FileInfo from,
                                        gold.debug.windowstolinux.appapi.file.model.FileInfo to, boolean directory,
                                        TaskSnapshot task, CancelToken cancel) {
                    return conflict;
                }

                @Override
                public gold.debug.windowstolinux.appapi.file.action.DeleteEffect delete(
                    Map<gold.debug.windowstolinux.appapi.file.action.DeleteEffect, ActionIntent> choices,
                    String explanation, TaskSnapshot task, CancelToken cancel) {
                    var effect = gold.debug.windowstolinux.appapi.file.action.DeleteEffect.PERMANENT;
                    return approval.apply(choices.get(effect), task) ? effect : null;
                }
            });
            capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION);
            opened.add(capabilities);
            source = runtime.capabilities().source(OWNER, VERSION, capabilities);
            opened.add(source);
            servers = runtime.capabilities().serverAccess(OWNER, VERSION, Optional.empty());
            opened.add(servers);
            logs = runtime.capabilities().logs(OWNER, VERSION, Optional.empty());
            opened.add(logs);
            var binding = runtime.tasks().bind(OWNER, VERSION, false);
            tasks = (TaskService) Proxy.newProxyInstance(TaskService.class.getClassLoader(), new Class<?>[]{TaskService.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("submit")) {
                        @SuppressWarnings("unchecked") var work = (TaskWork<Object>) arguments[1];
                        return binding.submit((TaskRequest) arguments[0], task -> {
                            contexts.put(task.id(), task);
                            return work.run(task);
                        });
                    }
                    if (method.getName().equals("registerRecovery")) {
                        var recovery = (TaskRecovery) arguments[1];
                        return binding.registerRecovery((String) arguments[0], (previous, task) -> {
                            capabilities.closeTaskResources(task);
                            return recovery.reconcile(previous, task);
                        });
                    }
                    try {
                        return method.invoke(binding, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            var file = new PlatformFileService(source);
            var context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "file" -> file;
                    case "tasks" -> tasks;
                    case "servers" -> servers;
                    case "logs" -> logs;
                    case "identity" -> new ModIdentity(OWNER, VERSION, Optional.empty());
                    case "launchMode" -> ModLaunchMode.NORMAL;
                    default ->
                        throw new UnsupportedOperationException("Not a Files service capability: " + method.getName());
                });
            service = new FilesService(context);
            for (var type : FilesRecovery.taskTypes()) {
                var registration = tasks.registerRecovery(type, service.recovery());
                registrations.add(registration);
                opened.add(registration);
            }
        } catch (Exception | Error failure) {
            for (var resource : opened.reversed())
                try {
                    resource.close();
                } catch (Exception | Error cleanup) {
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                }
            throw failure;
        }
    }

    /** Returns the genuine platform-bound Files service. / 返回真实平台绑定Files服务。
     * @return original service / 原服务 */
    public FilesService files() {
        return service;
    }

    /** Returns real isolated runtime for precise evidence assertions. / 返回实际隔离运行时用于精确依据断言。
     * @return original runtime / 原运行时 */
    public PlatformRuntime runtime() {
        return runtime;
    }

    /** Returns physical local test storage. / 返回物理本机测试存储。
     * @return local root / 本机根 */
    public Path local() {
        return local;
    }

    /** Returns physical storage behind the actual controlled SFTP server. / 返回实际受控SFTP服务器后的物理存储。
     * @return remote physical root / 远端物理根 */
    public Path remoteFiles() {
        return remote.files;
    }

    /** Returns the explicitly saved controlled-server identity. / 返回明确保存的受控服务器身份。
     * @return actual server identity / 实际服务器身份 */
    public ServerId server() {
        return new ServerId("server");
    }

    /** Models Linux link metadata over actual Windows links while retaining real SFTP I/O. / 在实际Windows链接上表达Linux链接元数据，保留真实SFTP I/O。
     * @throws Exception if the actual protocol subsystem is unavailable / 实际协议子系统不可用时 */
    public void linuxLinks() throws Exception {
        var subsystem = new org.apache.sshd.sftp.server.SftpSubsystemFactory() {
            @Override
            public org.apache.sshd.server.command.Command createSubsystem(org.apache.sshd.server.channel.ChannelSession channel) {
                var actual = new org.apache.sshd.sftp.server.SftpSubsystem(channel, this) {
                    /** Applies Linux REMOVE only to the actual native Link itself. / 仅对实际原生Link自身应用Linux REMOVE。
                     * @param id original protocol request / 原协议请求
                     * @param path original wire path / 原编码路径
                     * @throws java.io.IOException if exact native link deletion fails / 精确原生链接删除失败时 */
                    @Override
                    protected void doRemoveFile(int id, String path) throws java.io.IOException {
                        Path rooted = resolveFile(path);
                        if (gold.debug.windowstolinux.app.windows.file.WindowsLinks.target(remotePath(rooted)).isPresent())
                            super.doRemove(id, rooted, false);
                        else super.doRemoveFile(id, path);
                    }
                };
                getRegisteredListeners().forEach(actual::addSftpEventListener);
                return actual;
            }
        };
        subsystem.setFileSystemAccessor(new org.apache.sshd.sftp.server.SftpFileSystemAccessor() {
            @Override
            public java.util.NavigableMap<String, Object> resolveReportedFileAttributes(
                org.apache.sshd.sftp.server.SftpSubsystemProxy subsystem, Path path, int flags,
                java.util.NavigableMap<String, Object> attributes, java.nio.file.LinkOption... options) throws java.io.IOException {
                if (gold.debug.windowstolinux.app.windows.file.WindowsLinks.target(remotePath(path)).isPresent()) {
                    attributes.put("isSymbolicLink", true);
                    attributes.put("isDirectory", false);
                    attributes.put("isRegularFile", false);
                }
                return attributes;
            }

            @Override
            public String resolveLinkTarget(org.apache.sshd.sftp.server.SftpSubsystemProxy subsystem, Path path) throws java.io.IOException {
                Path nativePath = remotePath(path);
                String text = gold.debug.windowstolinux.app.windows.file.WindowsLinks.target(nativePath)
                    .orElseThrow(() -> new java.io.IOException("Not an actual fixture link"));
                Path target = Path.of(text);
                if (!target.isAbsolute()) target = nativePath.getParent().resolve(target).normalize();
                if (!target.startsWith(remote.files))
                    throw new java.nio.file.AccessDeniedException("Link escapes controlled remote root");
                return "/" + remote.files.relativize(target).toString().replace('\\', '/');
            }

            @Override
            public void createLink(org.apache.sshd.sftp.server.SftpSubsystemProxy subsystem, Path link, Path existing,
                                   boolean symbolic) throws java.io.IOException {
                Path nativeLink = remotePath(link);
                Path nativeTarget = existing.isAbsolute() ? remotePath(existing) : nativeLink.getParent().resolve(existing.toString()).normalize();
                if (!nativeLink.startsWith(remote.files) || !nativeTarget.startsWith(remote.files))
                    throw new java.nio.file.AccessDeniedException("Link escapes controlled remote root");
                if (symbolic) gold.debug.windowstolinux.app.windows.file.WindowsLinks.create(nativeLink, nativeTarget);
                else Files.createLink(nativeLink, nativeTarget);
            }
        });
        remote.listener.server().setSubsystemFactories(List.of(subsystem));
    }

    /** Maps only an exact controlled rooted path without following a native Link ancestor. / 仅映射精确受控根路径，不跟随原生Link祖先。
     * @param path original rooted protocol path / 原带根协议路径
     * @return actual test-owned no-follow path / 实际测试所属不跟随路径
     * @throws java.io.IOException if controlled root ownership is unproved / 受控根归属未证明时 */
    private Path remotePath(Path path) throws java.io.IOException {
        var fs = (org.apache.sshd.common.file.root.RootedFileSystem) path.getFileSystem();
        Path controlled = remote.files.toAbsolutePath().normalize();
        Path nativePath = fs.getRoot().resolve(path.toAbsolutePath().toString().substring(1)).toAbsolutePath().normalize();
        if (!nativePath.startsWith(controlled))
            throw new java.nio.file.AccessDeniedException("Wire path escapes controlled remote root");
        for (Path parent = nativePath.getParent(); parent != null && parent.startsWith(controlled); parent = parent.getParent())
            if (gold.debug.windowstolinux.app.windows.file.WindowsLinks.target(parent).isPresent())
                throw new java.nio.file.AccessDeniedException("Wire path implicitly follows a native Link ancestor");
        return nativePath;
    }

    /** Sets the next actual platform selection. / 设置下一次实际平台选择。
     * @param path physical selected directory / 物理选定目录 */
    public void choose(Path path) {
        chosen = path;
        chooser = (task, cancel) -> chosen;
    }

    /** Controls only the genuine host picker interaction, including delayed dismissal. / 仅控制真实宿主选择交互，包含延迟关闭。
     * @param interaction original task and cancellation based interaction / 根据原任务及取消的交互 */
    public void chooser(BiFunction<TaskSnapshot, CancelToken, Path> interaction) {
        chooser = interaction;
    }

    /** Changes only the actual human approval presenter. / 仅更改实际人工审批呈现器。
     * @param policy explicit fixture decision / 明确夹具决定 */
    public void approval(BiFunction<ActionIntent, TaskSnapshot, Boolean> policy) {
        approval = policy;
    }

    /** Sets the actual platform collision choice. / 设置实际平台冲突选项。
     * @param choice platform choice index / 平台选项索引 */
    public void conflict(int choice) {
        conflict = choice;
    }

    /** Waits for the original task's actual completion within the existing file deadline. / 在既有文件期限内等待原任务实际结束。
     * @param <T> copied payload type / 独立载荷类型
     * @param handle actual admitted task / 实际已准入任务
     * @return actual completion result / 实际结束结果
     * @throws Exception if actual completion fails or remains retained / 实际结束失败或仍保留时 */
    public <T> OperationResult<T> result(TaskHandle<T> handle) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        var completion = handle.completion().toCompletableFuture();
        while (!completion.isDone() && System.nanoTime() < deadline) {
            if (handle.snapshot().state() == TaskState.UNKNOWN)
                throw new AssertionError("Original task requires reconciliation: " + handle.snapshot()
                    + "; actual Files observation=" + service.observation(handle.id()));
            Thread.sleep(5);
        }
        return completion.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    }

    /** Waits for actual returned callback facts without waiting for UNKNOWN completion. / 等待实际已返回回调事实，不等UNKNOWN结束。
     * @param handle actual admitted task / 实际已准入任务
     * @return actual immutable observations / 实际不可变观测
     * @throws Exception if callback facts do not arrive / 回调事实未到达时 */
    public FilesObservation returned(TaskHandle<?> handle) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            var observed = service.observation(handle.id());
            if (observed.isPresent() && observed.orElseThrow().callbackReturned()) {
                var context = contexts.get(handle.id());
                if (context != null) FilePlatformFixture.callbackReturned(runtime, context);
                return observed.orElseThrow();
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Original Files callback facts were not returned: " + handle.snapshot());
    }

    /** Performs explicit original-task reconciliation without replay. / 显式核对原任务，不重放。
     * @param handle original admitted task / 原已准入任务
     * @throws Exception if original recovery fails / 原恢复失败时 */
    public void reconcile(TaskHandle<?> handle) throws Exception {
        returned(handle);
        runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    /** Asserts real explicit-resource and business-Schema absence. / 断言实际显式资源及业务Schema不存在。
     * @throws Exception if real evidence is unavailable / 实际依据不可用时 */
    public void noResources() throws Exception {
        assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_schema"));
    }

    /** Waits for actual platform task termination without widening existing file deadlines. / 不扩大既有文件期限地等待实际平台任务结束。
     * @throws Exception if any actual task remains unfinished / 任何实际任务仍未结束时 */
    public void awaitIdle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (runtime.tasks().snapshots().stream().anyMatch(task -> task.state() != TaskState.ENDED) && System.nanoTime() < deadline)
            Thread.sleep(5);
        assertTrue(runtime.tasks().snapshots().stream().allMatch(task -> task.state() == TaskState.ENDED), () -> "Actual platform tasks remain: " + runtime.tasks().snapshots());
        noResources();
    }

    /** Stops the actual protocol server and platform capabilities, preserving failures. / 停止实际协议服务器及平台能力，保留失败。
     * @throws Exception if actual cleanup fails / 实际清理失败时 */
    @Override
    public void close() throws Exception {
        Throwable failure = null;
        try {
            awaitIdle();
        } catch (Exception | Error unfinished) {
            failure = unfinished;
        }
        var owned = new ArrayList<AutoCloseable>(registrations);
        owned.add(source);
        owned.add(servers);
        owned.add(logs);
        owned.add(capabilities);
        owned.add(runtime);
        if (remote != null) owned.add(remote);
        for (var resource : owned)
            try {
                resource.close();
            } catch (Exception | Error unclosed) {
                if (failure == null) failure = unclosed;
                else if (unclosed != failure) failure.addSuppressed(unclosed);
            }
        if (failure instanceof Error error) throw error;
        if (failure instanceof Exception exception) throw exception;
        clearNativeLinks();
    }

    /** Removes only actual test-owned native links after all runtime resources closed. / 全部运行时资源关闭后仅删除实际测试所属原生链接。
     * @throws java.io.IOException if exact link cleanup cannot be verified / 无法核验精确链接清理时 */
    private void clearNativeLinks() throws java.io.IOException {
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path directory, java.nio.file.attribute.BasicFileAttributes attributes) throws java.io.IOException {
                owned(directory);
                if (gold.debug.windowstolinux.app.windows.file.WindowsLinks.target(directory).isPresent()) {
                    Files.delete(directory);
                    return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path path, java.nio.file.attribute.BasicFileAttributes attributes) throws java.io.IOException {
                owned(path);
                if (gold.debug.windowstolinux.app.windows.file.WindowsLinks.target(path).isPresent())
                    Files.delete(path);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFileFailed(Path path, java.io.IOException failure) throws java.io.IOException {
                owned(path);
                if (gold.debug.windowstolinux.app.windows.file.WindowsLinks.target(path).isPresent()) {
                    Files.delete(path);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                throw failure;
            }
        });
    }

    /** Rejects cleanup outside this exact isolated root. / 拒绝在此精确隔离根之外清理。
     * @param path actual visited object / 实际访问对象
     * @throws java.io.IOException if root ownership is not proved / 根归属未证明时 */
    private void owned(Path path) throws java.io.IOException {
        if (!path.toAbsolutePath().normalize().startsWith(root))
            throw new java.nio.file.AccessDeniedException("Native link cleanup escapes the isolated test root");
    }
}
