package gold.debug.windowstolinux.web.service.contract;

/**
 * Carries request ownership assigned by the trusted composition root rather than browser input.
 * <p>携带受信任组合根分配的请求归属，不采用浏览器输入。
 *
 * @param workspaceId server-assigned workspace identifier / 服务端分配的工作区标识
 * @param userId user id / 用户标识
 */
public record WebRequestContext(String workspaceId, String userId) {
}
