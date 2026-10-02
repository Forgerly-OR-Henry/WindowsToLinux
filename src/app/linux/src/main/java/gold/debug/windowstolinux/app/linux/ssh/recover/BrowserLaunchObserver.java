package gold.debug.windowstolinux.app.linux.ssh.recover;

/** Binds browser creation to host-owned durable resource facts. / 将浏览器创建绑定到宿主持有的持久资源事实。 */
public interface BrowserLaunchObserver {
    /** Persists original ownership before creating a driver or browser. / 创建驱动或浏览器前持久化原归属。
     * @throws Exception if ownership cannot be recorded / 无法登记归属时 */
    void preparing() throws Exception;

    /** Records the exact browser PID before terminal use. / 使用终端前记录精确浏览器 PID。
     * @param process actual owned browser process / 实际所属浏览器进程
     * @throws Exception if identity cannot be recorded / 无法登记身份时 */
    void started(BrowserProcess process) throws Exception;
}
