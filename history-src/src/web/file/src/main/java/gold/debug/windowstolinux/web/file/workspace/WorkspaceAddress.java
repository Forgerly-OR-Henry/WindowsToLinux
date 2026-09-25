package gold.debug.windowstolinux.web.file.workspace;

/**
 * Carries service-assigned identifiers rather than an HTTP-client-supplied filesystem path.
 * <p>携带服务分配的标识，不接受 HTTP 客户端提供的文件系统路径。
 *
 * @param workspaceId server-assigned workspace identifier / 服务端分配的工作区标识
 * @param resourceId resource id / 资源标识
 */
public record WorkspaceAddress(String workspaceId, String resourceId) {
    /**
     * Validates and binds the inputs required by workspace address.
     * <p>校验并绑定工作区地址所需输入。
     *
     * @param workspaceId server-assigned workspace identifier / 服务端分配的工作区标识
     * @param resourceId resource id / 资源标识
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public WorkspaceAddress {
        if (workspaceId == null || !workspaceId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,100}") || resourceId == null
                || !resourceId.matches("[a-f0-9-]{36}"))
            throw new IllegalArgumentException("Invalid workspace address");
    }

    /**
     * Returns marker.
     * <p>返回标记。
     *
     * @return marker / 标记
     */
    public String marker() {
        return workspaceId + ":" + resourceId;
    }
}
