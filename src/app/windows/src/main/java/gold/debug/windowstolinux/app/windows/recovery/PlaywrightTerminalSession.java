package gold.debug.windowstolinux.app.windows.recovery;

import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import com.microsoft.playwright.*;
import gold.debug.windowstolinux.shared.model.recovery.*;

/**
 * Owns every Playwright object on one serial event-pumping thread. / 在单一串行事件线程上持有全部 Playwright 对象。
 */
public final class PlaywrightTerminalSession implements BrowserTerminalSession {
    /**
     * SELECTOR.
     * <p>选择器。
     */
    private static final String SELECTOR = ".xterm, canvas, [role=log], pre[data-terminal], textarea[data-terminal], [data-wtl-terminal]";

    /**
     * Bound scheduled executor service collaborator for worker.
     * <p>处理工作线程的Scheduled执行器服务协作对象。
     */
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ssh-rescue-browser");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Closed.
     * <p>已关闭。
     */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Candidates.
     * <p>候选集合。
     */
    private final Map<String, Binding> candidates = new LinkedHashMap<>();

    /**
     * Headless.
     * <p>无界面。
     */
    private final boolean headless;

    /**
     * Initial url.
     * <p>初始URL。
     */
    private final String initialUrl;

    /**
     * Playwright.
     * <p>Playwright 浏览器驱动。
     */
    private Playwright playwright;

    /**
     * Browser.
     * <p>浏览器。
     */
    private Browser browser;

    /**
     * Facts and dependencies scoped to the current operation.
     * <p>限定于当前操作的事实及依赖。
     */
    private BrowserContext context;

    /**
     * Bound.
     * <p>边界。
     */
    private Binding bound;

    /**
     * Generation.
     * <p>代次。
     */
    private long generation;

    /**
     * Event pump.
     * <p>事件Pump。
     */
    private ScheduledFuture<?> eventPump;
    /**
     * Binds one page, frame and terminal element to the URL observed during selection.
     * <p>将页面、框架及终端元素绑定到选择时观测的 URL。
     *
     * @param page page / 页面
     * @param frame frame / 框架
     * @param element element / 元素
     * @param url URL address / URL 地址
     */
    private record Binding(Page page, Frame frame, ElementHandle element, String url) {
    }

    /**
     * Creates the interactive browser executor. / 创建交互式浏览器执行器。
     */
    public PlaywrightTerminalSession() {
        this(false, "about:blank");
    }

    /**
     * Binds the supplied dependencies and state for playwright terminal session.
     * <p>为Playwright终端会话绑定传入的依赖及状态。
     *
     * @param headless headless / 无界面
     * @param initialUrl initial url / 初始URL
     */
    PlaywrightTerminalSession(boolean headless, String initialUrl) {
        this.headless = headless;
        this.initialUrl = initialUrl;
    }

    /**
     * Starts an isolated browser context on its owner thread, registers terminal invalidation listeners and opens the configured console page.
     * <p>在所有者线程启动隔离浏览器上下文、登记终端失效监听器，并打开配置的控制台页面。
     *
     * @throws BrowserRecoveryException if browser work, terminal observation, interruption or owned cleanup cannot complete / 浏览器工作、终端观测、中断处理或自有资源清理无法完成时
     */
    @Override
    public void open() {
        call(() -> {
            if (browser != null && browser.isConnected()) {
                if (context.pages().isEmpty())
                    context.newPage();
                return null;
            }
            if (playwright != null) {
                playwright.close();
                playwright = null;
            }
            bound = null;
            generation++;
            candidates.clear();
            playwright = Playwright.create(new Playwright.CreateOptions()
                    .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1", "DEBUG", "", "PWDEBUG", "0")));
            var launch = new BrowserType.LaunchOptions().setHeadless(headless);
            String channel = installedChannel();
            if (channel != null)
                launch.setChannel(channel);
            browser = playwright.chromium().launch(launch);
            context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1280, 800));
            context.setDefaultTimeout(4000);
            context.onPage(this::watch);
            browser.onDisconnected(ignored -> {
                bound = null;
                generation++;
            });
            context.newPage().navigate(initialUrl);
            return null;
        });
        if (eventPump != null)
            return;
        eventPump = worker.scheduleWithFixedDelay(() -> {
            try {
                if (browser != null && browser.isConnected() && !context.pages().isEmpty())
                    context.pages().getFirst().waitForTimeout(25);
            } catch (RuntimeException ignored) {
                // Event failure invalidates every action until manual rebinding. / 事件失败使全部动作失效，等待人工重新绑定。
                bound = null;
                generation++;
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }

    /**
     * Invalidates a bound terminal after relevant socket closure or network failure on its page.
     * <p>绑定终端所在页面发生相关连接关闭或网络失败后使终端失效。
     *
     * @param page page / 页面
     */
    private void watch(Page page) {
        page.onWebSocket(socket -> socket.onClose(ignored -> {
            if (bound != null && bound.page() == page) {
                bound = null;
                generation++;
            }
        }));
        page.onRequestFailed(request -> {
            if (bound != null && bound.page() == page && Set.of("xhr", "fetch").contains(request.resourceType())) {
                bound = null;
                generation++;
            }
        });
        page.onClose(ignored -> {
            if (bound != null && bound.page() == page) {
                bound = null;
                generation++;
            }
        });
        page.onFrameNavigated(frame -> {
            if (bound != null && (bound.frame() == frame || bound.page().mainFrame() == frame)) {
                bound = null;
                generation++;
            }
        });
    }

    /**
     * Tests the available predicate against the supplied evidence.
     * <p>根据所提供证据检查可用条件。
     *
     * @return true when available predicate against the supplied evidence, false otherwise / 根据所提供证据检查可用条件时为 true，否则为 false
     */
    @Override
    public boolean available() {
        if (closed.get())
            return false;
        return call(() -> browser != null && browser.isConnected() && !context.pages().isEmpty());
    }

    /**
     * Returns targets.
     * <p>返回目标集合。
     *
     * @return targets / 目标集合
     * @throws BrowserRecoveryException if browser work, terminal observation, interruption or owned cleanup cannot complete / 浏览器工作、终端观测、中断处理或自有资源清理无法完成时
     */
    @Override
    public List<TerminalTarget> targets() {
        return call(() -> {
            requireOpen();
            candidates.clear();
            List<TerminalTarget> result = new ArrayList<>();
            for (Page page : context.pages())
                for (Frame frame : page.frames()) {
                    if (result.size() >= 64)
                        break;
                    try {
                        for (ElementHandle element : frame.querySelectorAll(SELECTOR)) {
                            if (!element.isVisible() || result.size() >= 64)
                                continue;
                            // Nested canvases inside xterm must not become a second input target. / xterm 内嵌画布不得成为第二个输入目标。
                            if (Boolean.TRUE
                                    .equals(element.evaluate("e => e.matches('canvas') && !!e.closest('.xterm')")))
                                continue;
                            String id = UUID.randomUUID().toString();
                            candidates.put(id, new Binding(page, frame, element, frame.url()));
                            result.add(new TerminalTarget(id, (result.size() + 1) + " · " + origin(frame.url()) + " · "
                                    + element.evaluate("e => e.tagName.toLowerCase()")));
                        }
                    } catch (PlaywrightException ignored) { // Detached frames are not selectable. / 脱离页面的框架不可选择。
                    }
                }
            return List.copyOf(result);
        });
    }

    /**
     * Binds playwright terminal session.
     * <p>绑定Playwright终端会话。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @return bind as a numeric result / 绑定的数值结果
     * @throws BrowserRecoveryException if browser work, terminal observation, interruption or owned cleanup cannot complete / 浏览器工作、终端观测、中断处理或自有资源清理无法完成时
     */
    @Override
    public long bind(String id) {
        return call(() -> {
            Binding candidate = candidates.get(id);
            if (candidate == null || !attached(candidate))
                throw new IllegalStateException("terminal-unavailable");
            bound = candidate;
            generation++;
            return generation;
        });
    }

    /**
     * Checks the exact terminal generation, reads accessible text first and captures bounded image evidence when text is unavailable.
     * <p>检查精确终端代次，优先读取可访问文本，并在文本不可用时捕获有界图像证据。
     *
     * @param expected identity, value or state required for verification / 验证要求的身份、内容或状态
     * @return constructed or resolved terminal observation / 构造或解析得到的终端观测
     * @throws BrowserRecoveryException if browser work, terminal observation, interruption or owned cleanup cannot complete / 浏览器工作、终端观测、中断处理或自有资源清理无法完成时
     */
    @Override
    public TerminalObservation observe(long expected) {
        try {
            return call(() -> {
                requireBound(expected);
                String text = (String) bound.element()
                        .evaluate("e => { const a = e.querySelector('.xterm-accessibility-tree'); "
                                + "if (a) return a.innerText.slice(-32000); if (e.matches('canvas,.xterm')) return ''; "
                                + "return (e.matches('textarea') ? e.value : e.innerText || '').slice(-32000); }");
                byte[] image = text.isBlank() ? bound.element().screenshot() : new byte[0];
                requireBound(expected);
                return new TerminalObservation(text, image, generation);
            });
        } catch (BrowserRecoveryException failure) {
            if (failure.failure().definition() == BrowserRecoveryFailureType.INTERRUPTED)
                throw failure;
            throw new BrowserRecoveryException(BrowserRecoveryFailureType.OBSERVATION_FAILED, failure);
        }
    }

    /**
     * Checks the exact bound terminal generation before typing the approved literal command and pressing Enter on its owning page.
     * <p>在所属页面键入已批准字面命令并按 Enter 前，检查精确终端绑定代次。
     *
     * @param expected identity, value or state required for verification / 验证要求的身份、内容或状态
     * @param command fixed or explicitly reviewed command text / 固定或显式审阅的命令文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws BrowserRecoveryException if browser work, terminal observation, interruption or owned cleanup cannot complete / 浏览器工作、终端观测、中断处理或自有资源清理无法完成时
     */
    @Override
    public void submit(long expected, String command) {
        if (command.isBlank() || command.length() > 4096 || command.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("invalid-terminal-line");
        call(() -> {
            requireBound(expected);
            ElementHandle input = bound.element().querySelector("textarea.xterm-helper-textarea");
            if (input == null)
                input = bound.element();
            input.evaluate("e => { if (!e.hasAttribute('tabindex')) e.tabIndex = 0; e.focus(); }");
            if (!Boolean.TRUE.equals(input.evaluate("e => e.ownerDocument.activeElement === e")))
                throw new IllegalStateException("terminal-input-unavailable");
            requireBound(expected);
            // Keyboard events go to the bound page even when another desktop window has focus. / 即使其他桌面窗口获得焦点，键盘事件仍发送到已绑定页面。
            bound.page().keyboard().type(command);
            requireBound(expected);
            if (!Boolean.TRUE.equals(input.evaluate("e => e.ownerDocument.activeElement === e")))
                throw new IllegalStateException("terminal-input-changed");
            bound.page().keyboard().press("Enter");
            return null;
        });
    }

    /**
     * Tests the valid predicate against the supplied evidence.
     * <p>根据所提供证据检查有效条件。
     *
     * @param expected identity, value or state required for verification / 验证要求的身份、内容或状态
     * @return true when valid predicate against the supplied evidence, false otherwise / 根据所提供证据检查有效条件时为 true，否则为 false
     * @throws BrowserRecoveryException if browser work, terminal observation, interruption or owned cleanup cannot complete / 浏览器工作、终端观测、中断处理或自有资源清理无法完成时
     */
    @Override
    public boolean valid(long expected) {
        if (closed.get())
            return false;
        return call(() -> expected == generation && bound != null && attached(bound));
    }

    /**
     * Clears the terminal binding and advances its generation on the browser owner thread unless already closed.
     * <p>尚未关闭时，在浏览器所有者线程清空终端绑定并递增其代次。
     *
     * @throws BrowserRecoveryException if browser work, terminal observation, interruption or owned cleanup cannot complete / 浏览器工作、终端观测、中断处理或自有资源清理无法完成时
     */
    @Override
    public void invalidate() {
        if (!closed.get())
            call(() -> {
                bound = null;
                generation++;
                return null;
            });
    }

    /**
     * Tests the attached predicate against the supplied evidence.
     * <p>根据所提供证据检查已附加条件。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return true when attached predicate against the supplied evidence, false otherwise / 根据所提供证据检查已附加条件时为 true，否则为 false
     */
    private boolean attached(Binding value) {
        try {
            return browser.isConnected() && !value.page().isClosed() && !value.frame().isDetached()
                    && value.frame().url().equals(value.url()) && value.element().isVisible()
                    && Boolean.TRUE.equals(value.element().evaluate("e => e.isConnected && navigator.onLine"));
        } catch (PlaywrightException ignored) {
            // A detached or inaccessible element is never a valid command target. / 脱离或不可访问的元素不能作为命令目标。
            return false;
        }
    }

    /**
     * Requires open and rejects inputs outside the declared constraints.
     * <p>要求打开并拒绝超出已声明约束的输入。
     *
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private void requireOpen() {
        if (browser == null || !browser.isConnected() || context.pages().isEmpty())
            throw new IllegalStateException("browser-disconnected");
    }

    /**
     * Requires bound and rejects inputs outside the declared constraints.
     * <p>要求边界并拒绝超出已声明约束的输入。
     *
     * @param expected identity, value or state required for verification / 验证要求的身份、内容或状态
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private void requireBound(long expected) {
        requireOpen();
        if (expected != generation || bound == null || !attached(bound))
            throw new IllegalStateException("terminal-changed");
    }

    /**
     * Extracts only the URL origin, substituting a fixed label for malformed URLs so raw URL data is not exposed.
     * <p>仅提取 URL 源，并为格式无效 URL 替换固定标签，避免暴露原始 URL 数据。
     *
     * @param url URL address / URL 地址
     * @return only the URL origin, substituting a fixed label for malformed URLs so raw URL data is not exposed / 仅提取 URL 源，并为格式无效 URL 替换固定标签，避免暴露原始 URL 数据
     */
    private static String origin(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getScheme() + "://" + Objects.toString(uri.getHost(), "local")
                    + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        } catch (IllegalArgumentException ignored) {
            // A malformed URL gets a fixed label; its raw text may include credentials. / 非法地址使用固定标签，原文可能含凭据。
            return "local";
        }
    }

    /**
     * Returns installed channel.
     * <p>返回已安装通道。
     *
     * @return installed channel; null when no matching value is available / 已安装通道；没有匹配值时为 null
     */
    private static String installedChannel() {
        for (String base : List.of("ProgramFiles(x86)", "ProgramFiles", "LOCALAPPDATA")) {
            String folder = System.getenv(base);
            if (folder == null)
                continue;
            if (java.nio.file.Files
                    .isRegularFile(java.nio.file.Path.of(folder, "Microsoft", "Edge", "Application", "msedge.exe")))
                return "msedge";
            if (java.nio.file.Files
                    .isRegularFile(java.nio.file.Path.of(folder, "Google", "Chrome", "Application", "chrome.exe")))
                return "chrome";
        }
        return null;
    }

    /**
     * Serializes browser work on the owner executor, preserving interruption and translating worker failures into safe browser-owned descriptors.
     * <p>在所有者执行器串行执行浏览器工作，保留中断，并将工作线程失败转换为安全浏览器自有描述。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @return constructed or resolved T / 构造或解析得到的T
     * @throws BrowserRecoveryException if browser work, terminal observation, interruption or owned cleanup cannot complete / 浏览器工作、终端观测、中断处理或自有资源清理无法完成时
     */
    private <T> T call(Callable<T> action) {
        if (closed.get())
            throw new BrowserRecoveryException(BrowserRecoveryFailureType.OPERATION_FAILED, null);
        try {
            return worker.submit(action).get();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new BrowserRecoveryException(BrowserRecoveryFailureType.INTERRUPTED, failure);
        } catch (ExecutionException failure) {
            throw new BrowserRecoveryException(BrowserRecoveryFailureType.OPERATION_FAILED, failure.getCause());
        }
    }

    /**
     * Closes browser-owned resources on the owner thread, then awaits executor termination with bounded waits while preserving interruption.
     * <p>在所有者线程关闭浏览器自有资源，随后有界等待执行器终止，并保留中断状态。
     *
     * @throws BrowserRecoveryException if browser work, terminal observation, interruption or owned cleanup cannot complete / 浏览器工作、终端观测、中断处理或自有资源清理无法完成时
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true))
            return;
        boolean interrupted = Thread.interrupted();
        Future<?> cleanup = worker.submit(() -> {
            try {
                if (context != null)
                    context.close();
            } finally {
                try {
                    if (browser != null)
                        browser.close();
                } finally {
                    if (playwright != null)
                        playwright.close();
                }
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        try {
            while (true) {
                try {
                    cleanup.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    break;
                } catch (InterruptedException failure) {
                    interrupted = true;
                }
            }
        } catch (ExecutionException | TimeoutException failure) {
            throw new BrowserRecoveryException(BrowserRecoveryFailureType.CLEANUP_FAILED, null);
        } finally {
            worker.shutdownNow();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            try {
                while (true) {
                    try {
                        if (!worker.awaitTermination(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS))
                            throw new BrowserRecoveryException(BrowserRecoveryFailureType.CLEANUP_FAILED, null);
                        break;
                    } catch (InterruptedException failure) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted)
                    Thread.currentThread().interrupt();
            }
        }
    }
}
