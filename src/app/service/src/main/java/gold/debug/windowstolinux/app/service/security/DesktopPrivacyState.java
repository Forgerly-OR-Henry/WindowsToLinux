package gold.debug.windowstolinux.app.service.security;

import java.util.Optional;

import gold.debug.windowstolinux.appapi.task.result.Problem;

/** Native-window privacy confirmation, independent of platform authorization. / 独立于平台授权的原生窗口隐私确认。
 * @param pending whether the serialized native transition is unfinished / 串行原生转换是否未完成
 * @param failure last unconfirmed privacy transition / 最近未确认的隐私转换 */
public record DesktopPrivacyState(boolean pending, Optional<Problem> failure) {
}
