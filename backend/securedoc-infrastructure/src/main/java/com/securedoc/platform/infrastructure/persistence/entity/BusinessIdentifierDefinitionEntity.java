package com.securedoc.platform.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "business_identifier_definitions")
public class BusinessIdentifierDefinitionEntity {
    @Id private UUID id;
    @Column(name="tenant_id", nullable=false) private UUID tenantId;
    @Column(name="identifier_key", nullable=false) private String identifierKey;
    @Column(nullable=false) private String label;
    @Column(name="required_flag", nullable=false) private boolean required;
    @Column(name="primary_flag", nullable=false) private boolean primary;
    @Column(name="searchable_flag", nullable=false) private boolean searchable;
    @Column(name="extractable_flag", nullable=false) private boolean extractable;
    @Column(name="displayable_flag", nullable=false) private boolean displayable;
    @Column(name="display_order", nullable=false) private int displayOrder;
    @Column(name="active_flag", nullable=false) private boolean active;
    @Column(name="validation_type", nullable=false) private String validationType;
    @Column(name="exact_length") private Integer exactLength;
    @Column(name="min_length") private Integer minLength;
    @Column(name="max_length") private Integer maxLength;
    @Column(name="case_normalization", nullable=false) private String caseNormalization;
    @Column(name="strip_separators_flag", nullable=false) private boolean stripSeparators;
    @Column(name="allowed_values", columnDefinition="TEXT") private String allowedValues;
    @Column(name="custom_pattern", length=512) private String customPattern;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    @Column(name="updated_at", nullable=false) private Instant updatedAt;
    public UUID getId(){return id;} public void setId(UUID v){id=v;}
    public UUID getTenantId(){return tenantId;} public void setTenantId(UUID v){tenantId=v;}
    public String getIdentifierKey(){return identifierKey;} public void setIdentifierKey(String v){identifierKey=v;}
    public String getLabel(){return label;} public void setLabel(String v){label=v;}
    public boolean isRequired(){return required;} public void setRequired(boolean v){required=v;}
    public boolean isPrimary(){return primary;} public void setPrimary(boolean v){primary=v;}
    public boolean isSearchable(){return searchable;} public void setSearchable(boolean v){searchable=v;}
    public boolean isExtractable(){return extractable;} public void setExtractable(boolean v){extractable=v;}
    public boolean isDisplayable(){return displayable;} public void setDisplayable(boolean v){displayable=v;}
    public int getDisplayOrder(){return displayOrder;} public void setDisplayOrder(int v){displayOrder=v;}
    public boolean isActive(){return active;} public void setActive(boolean v){active=v;}
    public String getValidationType(){return validationType;} public void setValidationType(String v){validationType=v;}
    public Integer getExactLength(){return exactLength;} public void setExactLength(Integer v){exactLength=v;}
    public Integer getMinLength(){return minLength;} public void setMinLength(Integer v){minLength=v;}
    public Integer getMaxLength(){return maxLength;} public void setMaxLength(Integer v){maxLength=v;}
    public String getCaseNormalization(){return caseNormalization;} public void setCaseNormalization(String v){caseNormalization=v;}
    public boolean isStripSeparators(){return stripSeparators;} public void setStripSeparators(boolean v){stripSeparators=v;}
    public String getAllowedValues(){return allowedValues;} public void setAllowedValues(String v){allowedValues=v;}
    public String getCustomPattern(){return customPattern;} public void setCustomPattern(String v){customPattern=v;}
    public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;}
    public Instant getUpdatedAt(){return updatedAt;} public void setUpdatedAt(Instant v){updatedAt=v;}
}
