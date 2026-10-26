package gold.debug.windowstolinux.appapi.ui.page;

/** One owned presentation transition; finish and close run on the EDT.
 * / 一次所属界面过渡；结束和取消均在 EDT 调用。 */
public interface UiMotionHandle extends AutoCloseable {
    /** Applies the final visual frame if the owner remains active. / 所属者仍有效时应用最终视觉帧。 */
    void finish();

    /** Cancels without invoking another frame or any business action. / 取消且不再调用视觉帧或业务动作。 */
    @Override
    void close();
}
