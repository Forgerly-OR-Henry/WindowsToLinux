package gold.debug.windowstolinux.app.windows.window;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Hides only native windows belonging to this application's owned child process. / 仅隐藏当前应用所属子进程的原生窗口。 */
public final class WindowPrivacy {
    /** Explicit owned child process. / 显式所属子进程。 */
    private long processId;

    /** Desired state before and after the child process starts. / 子进程启动前后的期望状态。 */
    private boolean locked;

    /** Previously visible handles and minimized state. / 原先可见的句柄及最小化状态。 */
    private final Map<Long, Integer> hidden = new LinkedHashMap<>();

    /** Verifies process ownership before any native operation. / 原生操作前验证进程归属。
     * @param pid owned child process / 所属子进程
     */
    public synchronized void identify(long pid) {
        var process = ProcessHandle.of(pid).orElseThrow();
        boolean owned = false;
        for (var parent = process.parent(); parent.isPresent(); parent = parent.get().parent())
            if (parent.get().pid() == ProcessHandle.current().pid()) {
                owned = true;
                break;
            }
        if (!owned)
            throw new IllegalStateException("Owned window privacy operation failed");
        processId = pid;
        if (locked)
            hide();
    }

    /** Applies window privacy without stopping the owned process. / 应用窗口隐私保护，不停止所属进程。
     * @param value requested lock state / 请求锁定状态
     */
    public synchronized void setLocked(boolean value) {
        locked = value;
        if (processId == 0)
            return;
        if (value)
            hide();
        else {
            invoke("restore", hidden.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue())
                .collect(java.util.stream.Collectors.joining(";")));
            hidden.clear();
        }
    }

    /** Covers new native popup windows while locked. / 锁定时处理新建原生弹窗。 */
    public synchronized void refresh() {
        if (locked && processId != 0)
            hide();
    }

    /** Counts visible owned windows for acceptance. / 为验收统计可见自有窗口。
     * @return visible top-level windows / 可见顶层窗口数
     */
    public synchronized int visibleWindows() {
        return (int) invoke("query", "").lines().filter(line -> line.matches("[0-9]+:[0-9]+")).count();
    }

    /** Discards handles after child process cleanup. / 子进程清理后丢弃句柄。 */
    public synchronized void forget() {
        processId = 0;
        hidden.clear();
    }

    /** Records and hides visible owned windows. / 记录并隐藏可见所属窗口。 */
    private void hide() {
        for (String line : invoke("hide", "").lines().toList()) {
            if (!line.matches("[0-9]+:[0-9]+"))
                continue;
            var pair = line.split(":");
            hidden.putIfAbsent(Long.parseLong(pair[0]), Integer.parseInt(pair[1]));
        }
    }

    /** Runs a fixed Win32 adapter with numeric identity inputs only. / 仅以数字身份输入执行固定 Win32 适配器。
     * @param action fixed action / 固定动作
     * @param handles numeric handles and minimized flags / 数字句柄及最小化标记
     * @return bounded native metadata / 有界原生元数据
     */
    private String invoke(String action, String handles) {
        Process command = null;
        try (var input = WindowPrivacy.class.getResourceAsStream("window-privacy.ps1")) {
            if (processId == 0 || ProcessHandle.of(processId).filter(ProcessHandle::isAlive).isEmpty())
                return "";
            if (input == null)
                throw new java.io.IOException("privacy adapter unavailable");
            String script = "$browserProcessId=" + processId + "; $privacyAction='" + action + "'; $restoreHandles='"
                + handles + "';\n" + new String(input.readAllBytes(), StandardCharsets.UTF_8);
            String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            command = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                "-EncodedCommand", encoded).redirectErrorStream(true).start();
            command.getOutputStream().close();
            if (!command.waitFor(10, TimeUnit.SECONDS))
                throw new java.io.IOException("privacy adapter timed out");
            byte[] output = command.getInputStream().readNBytes(16384);
            if (command.exitValue() != 0 || output.length == 16384)
                throw new java.io.IOException("privacy adapter failed");
            return new String(output, StandardCharsets.UTF_8).strip();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Owned window privacy operation interrupted", failure);
        } catch (Exception failure) {
            throw new IllegalStateException("Owned window privacy operation failed");
        } finally {
            if (command != null && command.isAlive()) {
                command.descendants().forEach(ProcessHandle::destroyForcibly);
                command.destroyForcibly();
                try {
                    command.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
