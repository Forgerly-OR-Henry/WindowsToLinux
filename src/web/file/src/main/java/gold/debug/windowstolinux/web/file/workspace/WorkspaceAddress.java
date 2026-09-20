package gold.debug.windowstolinux.web.file.workspace;

/** Identifiers supplied by the service, never a path supplied by an HTTP client. */
public record WorkspaceAddress(String workspaceId, String resourceId) {
    public WorkspaceAddress {
        if (workspaceId == null || !workspaceId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,100}")
                || resourceId == null || !resourceId.matches("[a-f0-9-]{36}"))
            throw new IllegalArgumentException("Invalid workspace address");
    }
    public String marker() { return workspaceId + ":" + resourceId; }
}
