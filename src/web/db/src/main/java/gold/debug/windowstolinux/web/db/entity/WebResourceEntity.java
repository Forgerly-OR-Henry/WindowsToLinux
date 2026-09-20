package gold.debug.windowstolinux.web.db.entity;

import java.util.Map;

/** Common revision columns; every database operation also carries workspace identity. */
public abstract class WebResourceEntity {
    private String workspaceId;
    private String id;
    private String createdBy;
    private String name;
    private String document;
    private Long version;
    private String createdAt;
    private String updatedAt;
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String value) { workspaceId = value; }
    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String value) { createdBy = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getDocument() { return document; }
    public void setDocument(String value) { document = value; }
    public Long getVersion() { return version; }
    public void setVersion(Long value) { version = value; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String value) { createdAt = value; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String value) { updatedAt = value; }
    public abstract Map<String, Object> attributes();
    public abstract void attributes(Map<String, Object> values);
    public StoredResource stored() { return new StoredResource(id, name, attributes(), document, version, createdAt, updatedAt); }
}
