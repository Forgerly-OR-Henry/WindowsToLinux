package gold.debug.windowstolinux.appapi.linux.command;

/** Interpreter boundary of the built-in SSH command transport. / 内置 SSH 命令传输的解释器边界。 */
public final class SshCommandFrameProtocol {
    private static final String BASH = "/bin/bash --noprofile --norc -p";

    /** Exact script header requiring same-session SFTP admission of probe launch tools before SSH exec. / 要求在 SSH 执行前以同一会话的 SFTP 准入探测启动工具的精确脚本首行。 */
    public static final String PROTECTED_SYSTEM_PROBE_HEADER = "# WTL_SSH_PROTECTED_SYSTEM_PROBE_V1\n";

    /** Fixed launcher after the SSH server dispatches exec to the account shell.
     * Linux loader and libc startup settings are removed by that shell before the first product process.
     */
    public static final String FRAME_LAUNCHER =
        "unset LD_ASSUME_KERNEL LD_AUDIT LD_BIND_NOT LD_BIND_NOW LD_DEBUG LD_DEBUG_OUTPUT LD_DYNAMIC_WEAK "
        + "LD_HWCAP_MASK LD_LIBRARY_PATH LD_ORIGIN_PATH LD_POINTER_GUARD LD_PREFER_MAP_32BIT_EXEC LD_PRELOAD "
        + "LD_PROFILE LD_PROFILE_OUTPUT LD_SHOW_AUXV LD_TRACE_LOADED_OBJECTS LD_TRACE_PRELINKING LD_USE_LOAD_BIAS "
        + "LD_VERBOSE LD_WARN GLIBC_TUNABLES GCONV_PATH LOCPATH || exit 125\n"
        + "LC_ALL=C exec /usr/bin/env BASH_ENV=/dev/null ENV=/dev/null LC_ALL=C " + BASH + " -c";

    /** Fixed launcher for the framed script on fd 3, with original stdin on fd 0. */
    public static final String SCRIPT_LAUNCHER = "exec " + BASH + " /dev/fd/3";

    private SshCommandFrameProtocol() {
    }
}
