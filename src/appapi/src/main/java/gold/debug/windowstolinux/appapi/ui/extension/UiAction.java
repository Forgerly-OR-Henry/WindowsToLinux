package gold.debug.windowstolinux.appapi.ui.extension;

import java.util.Optional;

import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;

/** Lightweight EDT action callbacks; business work must use asynchronous tasks. / 轻量 EDT 动作回调，业务工作须使用异步任务。
 * @param <C> detached point context / 分离的位置上下文 */
public interface UiAction<C> {
    /** Describes current display without starting work. / 描述当前展示，不启动业务工作。
     * @param context current optional selection / 当前可选选择
     * @param environment current host environment / 当前宿主环境
     * @return localized presentation / 本地化展示
     * @throws Exception if presentation fails / 展示失败时 */
    UiActionPresentation presentation(Optional<C> context, UiEnvironment environment) throws Exception;

    /** Invokes with a captured context after live host checks. / 实时宿主核验后以固定上下文执行。
     * @param context captured click selection / 点击时固定的选择
     * @throws Exception if invocation fails / 执行失败时 */
    void invoke(Optional<C> context) throws Exception;
}
