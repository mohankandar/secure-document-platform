# LexaVault: how the main tables link

Generated with [DATA-DICTIONARY.md](DATA-DICTIONARY.md) by `scripts/Update-LexaVault-DataDictionary.ps1 (or .sh)`. Do not edit by hand.

Solid lines are foreign keys. Dotted lines are links the code joins on without a foreign key (listed under the diagram). Each box shows its primary key and the columns that link it. Nearly every table also has `tenant_id` → `tenants`: only the case's is drawn, and every query must filter on it.

```mermaid
erDiagram
    artifacts {
        uuid id PK
        uuid document_id FK
    }
    audit_records {
        uuid id PK
        varchar case_id
    }
    delivery_submissions {
        uuid id PK
        uuid package_id FK
    }
    derived_deliveries {
        uuid id PK
        uuid package_id FK
        uuid source_document_id FK
        uuid destination_connection_id FK
        uuid artifact_id FK
    }
    document_export_records {
        uuid id PK
        uuid export_record_id FK
        uuid document_id FK
    }
    document_packages {
        uuid id PK
        uuid tenant_id FK
        uuid business_record_id
    }
    documents {
        uuid id PK
        uuid package_id FK
    }
    external_intakes {
        uuid package_id PK, FK
        uuid integration_connection_id FK
    }
    findings {
        uuid id PK
        uuid document_id FK
    }
    fld_field_definition {
        uuid definition_id PK
        uuid entity_uuid
    }
    fld_record {
        uuid record_uuid PK
        uuid entity_uuid
        text record_id
    }
    fld_value {
        uuid record_uuid PK, FK
        text field_key PK
        smallint position PK
    }
    integration_connections {
        uuid id PK
    }
    package_export_records {
        uuid id PK
        uuid package_id FK
    }
    package_lifecycles {
        uuid package_id PK, FK
    }
    pipeline_jobs {
        uuid id PK
        uuid package_id FK
        uuid document_id
    }
    review_tasks {
        uuid id PK
        uuid package_id FK
    }
    tenants {
        uuid id PK
    }
    vault_entries {
        uuid id PK
        uuid package_id FK
        uuid document_id FK
        uuid artifact_id FK
    }
    documents ||--o{ artifacts : "document_id"
    document_packages ||--o{ delivery_submissions : "package_id"
    artifacts |o--o{ derived_deliveries : "artifact_id"
    integration_connections |o--o{ derived_deliveries : "destination_connection_id"
    documents ||--o{ derived_deliveries : "source_document_id"
    document_packages ||--o{ derived_deliveries : "package_id"
    documents ||--o{ document_export_records : "document_id"
    package_export_records ||--o{ document_export_records : "export_record_id"
    tenants ||--o{ document_packages : "tenant_id"
    document_packages ||--o{ documents : "package_id"
    integration_connections ||--o{ external_intakes : "integration_connection_id"
    document_packages ||--o{ external_intakes : "package_id"
    documents ||--o{ findings : "document_id"
    fld_record ||--o{ fld_value : "record_uuid"
    document_packages ||--o{ package_export_records : "package_id"
    document_packages ||--o{ package_lifecycles : "package_id"
    document_packages ||--o{ pipeline_jobs : "package_id"
    document_packages ||--o{ review_tasks : "package_id"
    artifacts ||--o{ vault_entries : "artifact_id"
    documents ||--o{ vault_entries : "document_id"
    document_packages ||--o{ vault_entries : "package_id"
    fld_record |o..o{ document_packages : "business_record_id"
    tenants |o..o{ fld_record : "entity_uuid"
    tenants |o..o{ fld_field_definition : "entity_uuid"
    document_packages |o..o{ audit_records : "case_id"
    documents |o..o{ pipeline_jobs : "document_id"
```

## Links without a foreign key

| From | To | How the code matches them |
|---|---|---|
| `document_packages.business_record_id` | `fld_record.record_id` | record_id = business_record_id as text, record_type business-record |
| `fld_record.entity_uuid` | `tenants.id` | entity_uuid = tenants.id |
| `fld_field_definition.entity_uuid` | `tenants.id` | entity_uuid = tenants.id |
| `audit_records.case_id` | `document_packages.id` | coalesce(case_id, resource_id) = package id as text |
| `pipeline_jobs.document_id` | `documents.id` | document_id = documents.id |

Every other table and link is in [DATA-DICTIONARY.md](DATA-DICTIONARY.md) (its foreign keys and "Referenced by" lines).
