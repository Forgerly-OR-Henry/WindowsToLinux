package gold.debug.windowstolinux.app.ui.ai;

import java.util.*;

/** Owns transient AI page state and clears its unlock buffer. / 持有临时 AI 页面状态并清理解锁缓冲区。 */
public final class AiPageState implements AutoCloseable {
    /** Short-lived unlock buffer. / 短生命周期解锁缓冲区。 */
    private final char[] masterPassword;
    /** Visible output. / 可见输出。 */
    private final String output;
    /** Nonsecret inventory draft. / 非秘密清单草稿。 */
    private final AiInventoryState inventory;
    /** Captures page state without retaining the caller's buffer. / 捕获页面状态且不持有调用方缓冲区。
     * @param masterPassword unlock input / 解锁输入
     * @param output visible output / 可见输出
     * @param inventory inventory draft / 清单草稿
     */
    public AiPageState(char[] masterPassword,String output,AiInventoryState inventory){
        this.masterPassword=masterPassword.clone();this.output=Objects.requireNonNull(output);this.inventory=Objects.requireNonNull(inventory);
    }
    /** Copies the unlock buffer. / 复制解锁缓冲区。
     * @return disposable copy / 可清理副本
     */
    public char[] masterPassword(){return masterPassword.clone();}
    /** Returns visible output. / 返回可见输出。
     * @return output / 输出
     */
    public String output(){return output;}
    /** Returns the immutable editor state. / 返回不可变编辑状态。
     * @return editor state / 编辑状态
     */
    public AiInventoryState inventory(){return inventory;}
    /** Clears the owned secret. / 清理持有的秘密。 */
    @Override public void close(){Arrays.fill(masterPassword,'\0');}
}
