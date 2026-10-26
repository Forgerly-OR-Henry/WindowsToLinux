package gold.debug.windowstolinux.appapi.ui.page;

/** Shared presentation timing, independent of business work. / 不依赖业务执行的共用动效时长。 */
public enum UiMotionKind {
    DETAIL(240), SIDEBAR(240), CONTENT(160), HOVER(120), PRESS(80),
    SWITCH(120), REORDER(180), POPUP(140), PROGRESS(160);

    private final int milliseconds;

    UiMotionKind(int milliseconds) {
        this.milliseconds = milliseconds;
    }

    /** Returns logical elapsed milliseconds, unaffected by UI scale. / 返回不受界面缩放影响的经过毫秒数。
     * @return duration / 时长 */
    public int milliseconds() {
        return milliseconds;
    }
}
