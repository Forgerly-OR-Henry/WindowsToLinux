package gold.debug.windowstolinux.appapi.linux.command;

/** Interpreter boundary of the built-in SSH command transport. / 内置 SSH 命令传输的解释器边界。 */
public final class SshCommandFrameProtocol {
    private static final String BASH = "/bin/bash --noprofile --norc -p";

    /** Exact script header requiring same-session SFTP admission of probe launch tools before SSH exec. / 要求在 SSH 执行前以同一会话的 SFTP 准入探测启动工具的精确脚本首行。 */
    public static final String PROTECTED_SYSTEM_PROBE_HEADER = "# WTL_SSH_PROTECTED_SYSTEM_PROBE_V1\n";

    /** Fixed host probe requiring the privilege-checked frame. / 要求使用特权校验帧的固定宿主探测。 */
    public static final String PRIVILEGE_CHECKED_SYSTEM_PROBE_HEADER = "# WTL_SSH_PROTECTED_SYSTEM_PROBE_V2\n";

    /** Fixed launcher after the SSH server dispatches exec to the account shell.
     * Linux loader and libc startup settings are removed by that shell before the first product process.
     */
    private static final String CLEAN_LOADER_ENVIRONMENT =
        "unset LD_ASSUME_KERNEL LD_AUDIT LD_BIND_NOT LD_BIND_NOW LD_DEBUG LD_DEBUG_OUTPUT LD_DYNAMIC_WEAK "
        + "LD_HWCAP_MASK LD_LIBRARY_PATH LD_ORIGIN_PATH LD_POINTER_GUARD LD_PREFER_MAP_32BIT_EXEC LD_PRELOAD "
        + "LD_PROFILE LD_PROFILE_OUTPUT LD_SHOW_AUXV LD_TRACE_LOADED_OBJECTS LD_TRACE_PRELINKING LD_USE_LOAD_BIAS "
        + "LD_VERBOSE LD_WARN GLIBC_TUNABLES GCONV_PATH LOCPATH || exit 125\n";

    private static final String FRAME_ENVIRONMENT =
        "/usr/bin/env BASH_ENV=/dev/null ENV=/dev/null LC_ALL=C " + BASH + " -c";

    public static final String FRAME_LAUNCHER = CLEAN_LOADER_ENVIRONMENT + "LC_ALL=C exec " + FRAME_ENVIRONMENT;

    /**
     * Uses the SFTP-admitted distro getcap as the first external program. SFTP v3 supplies mode
     * bits but has no Linux capability xattr query before this metadata reader runs.
     */
    public static final String PRIVILEGE_CHECKED_FRAME_LAUNCHER = CLEAN_LOADER_ENVIRONMENT + """
        caps=$(LC_ALL=C /usr/sbin/getcap /usr/sbin/getcap /usr/bin/setpriv /usr/bin/env /bin/bash /usr/bin/stat /usr/bin/readlink 2>&1) || exit 125
        case "$caps" in '') ;; *) exit 125 ;; esac
        unset caps || exit 125
        """ + "LC_ALL=C exec /usr/bin/setpriv --no-new-privs -- " + FRAME_ENVIRONMENT;

    /** Fixed launcher for the framed script on fd 3, with original stdin on fd 0. */
    public static final String SCRIPT_LAUNCHER = "exec " + BASH + " /dev/fd/3";

    private SshCommandFrameProtocol() {
    }
}
