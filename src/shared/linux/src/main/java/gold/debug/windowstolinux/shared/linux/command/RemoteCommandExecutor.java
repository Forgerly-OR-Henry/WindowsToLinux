package gold.debug.windowstolinux.shared.linux.command;

import java.time.Duration;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;

/** Transport-independent bounded command execution. / 与传输实现无关的有界命令执行。 */
public interface RemoteCommandExecutor {
    /** Executes a command with bounded evidence. / 执行命令并返回有界证据。
     * @param script exact command / 精确命令
     * @param timeout maximum duration / 最大时长
     * @param preserveOutput retain bounded output / 保留有界输出
     * @return actual result / 实际结果
     * @throws LinuxOperationException on transport failure / 传输失败时
     */
    RemoteCommandResult exec(String script, Duration timeout, boolean preserveOutput) throws LinuxOperationException;

    /** Executes a typed protocol command. / 执行类型化协议命令。
     * @param script exact command / 精确命令
     * @param timeout maximum duration / 最大时长
     * @param preserveOutput retain bounded output / 保留有界输出
     * @return actual result / 实际结果
     * @throws LinuxOperationException on transport failure / 传输失败时
     */
    RemoteCommandResult execProtocol(String script, Duration timeout, boolean preserveOutput)
            throws LinuxOperationException;

    /** Sends exact bounded input to a protocol command. / 向协议命令发送精确有界输入。
     * @param script exact command / 精确命令
     * @param input exact input / 精确输入
     * @param timeout maximum duration / 最大时长
     * @param maxBytes maximum output / 最大输出量
     * @return actual result / 实际结果
     * @throws LinuxOperationException on transport failure / 传输失败时
     */
    RemoteCommandResult execProtocolWithInput(String script, byte[] input, Duration timeout, long maxBytes)
            throws LinuxOperationException;
}
