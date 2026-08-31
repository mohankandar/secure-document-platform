package com.securedoc.platform.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "dashboard_column_settings")
public class DashboardColumnSettingEntity {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "column_key", nullable = false) private String columnKey;
    @Column(name = "allowed_flag", nullable = false) private boolean allowed;
    @Column(name = "default_visible_flag", nullable = false) private boolean defaultVisible;
    @Column(name = "display_order", nullable = false) private int displayOrder;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public String getColumnKey() { return columnKey; }
    public void setColumnKey(String columnKey) { this.columnKey = columnKey; }
    public boolean isAllowed() { return allowed; }
    public void setAllowed(boolean allowed) { this.allowed = allowed; }
    public boolean isDefaultVisible() { return defaultVisible; }
    public void setDefaultVisible(boolean defaultVisible) { this.defaultVisible = defaultVisible; }
    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int displayOrder) { this.displayOrder = displayOrder; }
}
