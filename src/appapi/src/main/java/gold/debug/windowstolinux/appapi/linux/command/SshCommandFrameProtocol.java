package gold.debug.windowstolinux.appapi.linux.command;

/** Interpreter boundary of the built-in SSH command transport. / 内置 SSH 命令传输的解释器边界。 */
public final class SshCommandFrameProtocol {
    private static final String BASH = "/bin/bash --noprofile --norc -p";

    /** Fixed launcher for the frame reader, after the SSH server dispatches exec. */
    public static final String FRAME_LAUNCHER =
        "exec /usr/bin/env BASH_ENV=/dev/null ENV=/dev/null LC_ALL=C " + BASH + " -c";

    /** Fixed launcher for the framed script on fd 3, with original stdin on fd 0. */
    public static final String SCRIPT_LAUNCHER = "exec " + BASH + " /dev/fd/3";

    private SshCommandFrameProtocol() {
    }
}
