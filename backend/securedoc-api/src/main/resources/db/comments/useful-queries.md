## Useful queries

Read-only queries for the owner and for anyone writing SQL. Replace the values in angle brackets. Each one is scoped to one
entity (`tenant_id`); never drop that condition.

### Find a case by contract or member number

The join `JdbcCasePrimaryIdentifierLookup` uses: a case (`document_packages`) has a business record in the case fields
(`fld_record`, record type `business-record`), whose values are in `fld_value`. Search any field by its key, or the
entity's main field (`primary_flag`). Values marked `sensitive` are never shown on screen; leave them out here too.

```sql
SELECT dp.id AS case_id, dp.status, dp.created_at, v.field_key, v.display_value
  FROM document_packages dp
  JOIN fld_record r
    ON r.entity_uuid = dp.tenant_id AND r.product_code = 'LEXAVAULT' AND r.record_type = 'business-record'
   AND r.record_id = cast(dp.business_record_id AS text)
  JOIN fld_value v
    ON v.record_uuid = r.record_uuid AND NOT v.sensitive
 WHERE dp.tenant_id = '<entity uuid>'
   AND lower(v.field_key) IN ('contract_number', 'member_number')   -- the keys your entity uses
   AND v.display_value = '<the number>';
```

The entity's main field and its label:

```sql
SELECT field_key, label FROM fld_field_definition
 WHERE entity_uuid = '<entity uuid>' AND product_code = 'LEXAVAULT' AND primary_flag AND active;
```

### Time per stage for a case (pipeline_jobs)

One row per job; a stage can have several jobs (one per document, page batch or destination). Finished jobs are deleted
after a while, so this works for recent cases.

```sql
SELECT stage, count(*) AS jobs, min(created_at) AS first_queued, max(updated_at) AS last_finished,
       max(updated_at) - min(created_at) AS elapsed, sum(attempts) AS attempts,
       count(*) FILTER (WHERE status <> 'DONE') AS not_done
  FROM pipeline_jobs
 WHERE tenant_id = '<entity uuid>' AND package_id = '<case uuid>'
 GROUP BY stage
 ORDER BY min(created_at);
```

### Whole-case time (audit_records)

From the first to the last audit record of the case. Records about the case itself carry its id in `resource_id`;
records about one of its documents carry it in `case_id`.

```sql
SELECT min(occurred_at) AS first_record, max(occurred_at) AS last_record,
       max(occurred_at) - min(occurred_at) AS whole_case, count(*) AS records
  FROM audit_records
 WHERE tenant_id = '<entity uuid>'
   AND coalesce(case_id, resource_id) = '<case uuid>';
```

The case's steps in order:

```sql
SELECT occurred_at, action, outcome, actor_display_name, resource_type
  FROM audit_records
 WHERE tenant_id = '<entity uuid>' AND coalesce(case_id, resource_id) = '<case uuid>'
 ORDER BY occurred_at;
```

### Findings by kind and how they were placed (task 0105 columns)

`reader` (how the page was read), `method` (how the item was found), `placement` (how its place on the page was fixed),
`person_reason` (why a person had to look). Findings saved before task 0105 have these empty.

```sql
SELECT f.entity_type, f.reader, f.method, f.placement, f.person_reason, f.status, count(*) AS findings
  FROM findings f
  JOIN documents d ON d.id = f.document_id
 WHERE f.tenant_id = '<entity uuid>'
   AND d.package_id = '<case uuid>'          -- or drop this line for the whole entity
 GROUP BY f.entity_type, f.reader, f.method, f.placement, f.person_reason, f.status
 ORDER BY findings DESC;
```
