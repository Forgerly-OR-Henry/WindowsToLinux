package gold.debug.windowstolinux.mod.deployment.ui.standard;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentDialogs;

/** Captures a one-shot owned UTF-8 password without retaining late EDT characters. / 不保留迟到 EDT 字符地获取一次性所属 UTF-8 密码。 */
final class DeploymentStandardPasswordInput {
    /** Prevents construction of the bounded UI input helper. / 禁止构造有界 UI 输入辅助类。 */
    private DeploymentStandardPasswordInput() {
    }

    /** Requests one owned byte array after task admission and clears every temporary input. / 任务准入后请求一份所属字节数组并清除全部临时输入。
     * @param page original page / 原页面
     * @param view original view generation / 原视图代次
     * @param task original task / 原任务
     * @param title localized modal title / 本地化模态标题
     * @param label localized field label / 本地化字段标签
     * @param allowEmpty whether socket authentication may use no password / socket 认证是否可无密码
     * @param onAccepted same-EDT accepted transition / 同 EDT 已接受状态转换
     * @return newly owned UTF-8 bytes / 新所属 UTF-8 字节
     * @throws Exception if the view or input is unavailable / 视图或输入不可用时 */
    static byte[] request(DeploymentStandardPage page, long view, TaskContext task, String title, String label,
                          boolean allowEmpty, Runnable onAccepted) throws Exception {
        var field = page.onEdt(JPasswordField::new);
        var form = page.onEdt(() -> {
            var value = DeploymentDialogs.form();
            value.add(DeploymentDialogs.text(label));
            value.add(field);
            return value;
        });
        boolean accepted = page.approveSecret(view, form, title, task, onAccepted);
        char[] chars = capture(field);
        try {
            if (!accepted)
                throw new CancellationException("Password input was cancelled");
            if (chars.length > 262_144 || !allowEmpty && chars.length == 0)
                throw new IllegalArgumentException("Password input size is invalid");
            var buffer = ByteBuffer.allocate(chars.length * 4);
            try {
                var encoder = StandardCharsets.UTF_8.newEncoder();
                var encoded = encoder.encode(CharBuffer.wrap(chars), buffer, true);
                if (encoded.isError())
                    encoded.throwException();
                var flushed = encoder.flush(buffer);
                if (flushed.isError())
                    flushed.throwException();
                if (buffer.position() > 1024 * 1024 || !allowEmpty && buffer.position() == 0)
                    throw new IllegalArgumentException("Password input size is invalid");
                return Arrays.copyOf(buffer.array(), buffer.position());
            } finally {
                Arrays.fill(buffer.array(), (byte) 0);
            }
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    /** Captures and clears the field even if the waiting worker is interrupted. / 即使等待线程中断也取得并清空字段。
     * @param field current password control / 当前密码控件
     * @return caller-owned characters / 调用方所属字符
     * @throws InterruptedException if the original worker was interrupted / 原工作线程中断时
     * @throws ExecutionException if the EDT capture failed / EDT 获取失败时 */
    private static char[] capture(JPasswordField field) throws InterruptedException, ExecutionException {
        var captured = new CompletableFuture<char[]>();
        var abandoned = new AtomicBoolean();
        SwingUtilities.invokeLater(() -> {
            char[] value = null;
            try {
                value = field.getPassword();
                if (abandoned.get())
                    Arrays.fill(value, '\0');
                else {
                    captured.complete(value);
                    value = null;
                }
            } catch (RuntimeException failure) {
                captured.completeExceptionally(failure);
            } finally {
                if (value != null)
                    Arrays.fill(value, '\0');
                field.setText("");
            }
        });
        try {
            return captured.get();
        } catch (InterruptedException interrupted) {
            abandoned.set(true);
            captured.thenAccept(value -> Arrays.fill(value, '\0'));
            throw interrupted;
        }
    }
}
