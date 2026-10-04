package gold.debug.windowstolinux.appapi.ai.voice;

import java.util.concurrent.CompletionStage;

import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;

/** Original-task realtime session; never changes model or replays uncertain audio. / 原任务实时会话；不切换模型或重放未知音频。 */
public interface VoiceSession extends AutoCloseable {
    /** Enqueues a sample-aligned chunk up to 64 KiB; BUSY means none was accepted. / 排队最多 64 KiB 且样本对齐的块；BUSY 表示未接收。
     * @param audio copied mono PCM16 samples / 复制的单声道 PCM16 样本
     * @return completion of the actual socket send / 实际套接字发送完成
     * @throws ApiException if authority, input, terminal state or backpressure rejects the chunk / 授权、输入、终态或背压拒绝音频时 */
    CompletionStage<Void> send(byte[] audio) throws ApiException;

    /** Commits one audio turn of at least 100 ms without ending the session. / 提交至少 100 ms 的音频轮次，不结束会话。
     * @return actual commit send / 实际提交发送
     * @throws ApiException if no valid turn or authority exists / 无有效轮次或授权时 */
    CompletionStage<Void> commit() throws ApiException;

    /** Seals input, commits remaining audio, waits for all final parts, then releases the socket. / 封闭输入，提交剩余音频，等全部定稿后释放套接字。
     * @return final result after actual resource cleanup / 实际资源清理后的最终结果
     * @throws ApiException if the task or turn is invalid / 任务或轮次无效时 */
    CompletionStage<OperationResult<VoiceResponse>> finish() throws ApiException;

    /** Observes terminal completion, including confirmed text on uncertainty. / 观察终态完成，包括未知状态下已确认文字。
     * @return terminal result after cleanup / 清理后的终态结果 */
    CompletionStage<OperationResult<VoiceResponse>> completion();

    /** Stops normal callbacks and releases connection and buffers; from a listener, cleanup completes after that callback returns. / 停止正常回调并释放连接和缓冲；在监听器内调用时，清理在该回调返回后完成。
     * @throws ApiException if resource cleanup cannot be confirmed / 无法确认资源清理时 */
    void cancel() throws ApiException;

    /** Cancels unfinished input without committing audio; BUSY means a listener must return before cleanup can finish. / 取消未结束输入而不提交音频；BUSY 表示监听器须先返回才能完成清理。
     * @throws ApiException if cleanup fails / 清理失败时 */
    @Override
    void close() throws ApiException;
}
