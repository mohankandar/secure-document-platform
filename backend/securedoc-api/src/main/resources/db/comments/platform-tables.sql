-- Task 0118: the meaning of every platform-core table and column, set by the schema step after the platform runs
-- (SchemaStep.platformComments). One COMMENT statement per line. A new platform-core table or column needs its line
-- here; the data dictionary's check (DataDictionaryPostgresTest in CI) fails without it.

-- idn_customer
COMMENT ON TABLE idn_customer IS '(platform-core) Customers, one per licence tenant, with the name from the licence and the default time zone for their entities.';
COMMENT ON COLUMN idn_customer.tenant_id IS 'The customer''s tenant ID from the licence (TEN-...), the key every other identity row points to.';
COMMENT ON COLUMN idn_customer.name IS 'The customer''s name as given in the licence.';
COMMENT ON COLUMN idn_customer.time_zone IS 'The customer''s IANA time zone, followed by every entity without its own, null until setup or the app sets it.';
COMMENT ON COLUMN idn_customer.created_at IS 'When the customer row was created.';
COMMENT ON COLUMN idn_customer.updated_at IS 'When the customer row was last changed.';

-- idn_entity
COMMENT ON TABLE idn_entity IS '(platform-core) The customer''s licensed entity slots and their status, name, activation and time zone, rows are never deleted.';
COMMENT ON COLUMN idn_entity.slot_id IS 'The licence''s entity slot ID (ENT-...), the key of this row.';
COMMENT ON COLUMN idn_entity.entity_uuid IS 'Name-based UUID derived from the slot ID, used as the key of the app''s own entity row.';
COMMENT ON COLUMN idn_entity.tenant_id IS 'The customer (tenant ID) this entity slot belongs to.';
COMMENT ON COLUMN idn_entity.status IS 'Slot status: NOT_ACTIVATED, ACTIVE, SUSPENDED, or NOT_IN_LICENCE when a newer licence dropped it.';
COMMENT ON COLUMN idn_entity.display_name IS 'The name the Business Admin gave the entity on activation, unique per customer ignoring case.';
COMMENT ON COLUMN idn_entity.activated_by IS 'Who activated the slot (the Business Admin''s actor ID), null if never activated.';
COMMENT ON COLUMN idn_entity.activated_at IS 'When the slot was activated, null if never activated.';
COMMENT ON COLUMN idn_entity.status_before_removal IS 'While NOT_IN_LICENCE, the status (ACTIVE or SUSPENDED) to restore if the slot returns to the licence.';
COMMENT ON COLUMN idn_entity.time_zone IS 'The entity''s own IANA time zone, null means it follows the customer''s time zone.';
COMMENT ON COLUMN idn_entity.created_at IS 'When the entity slot row was created.';
COMMENT ON COLUMN idn_entity.updated_at IS 'When the entity slot row was last changed.';

-- idn_grant_history
COMMENT ON TABLE idn_grant_history IS '(platform-core) Insert-only audit of every group-to-role grant change, per entity or platform-wide, written with the change.';
COMMENT ON COLUMN idn_grant_history.history_id IS 'Generated sequence number of the history row.';
COMMENT ON COLUMN idn_grant_history.tenant_id IS 'The customer (tenant ID) whose grant changed.';
COMMENT ON COLUMN idn_grant_history.product_code IS 'The product the grant applies to.';
COMMENT ON COLUMN idn_grant_history.slot_id IS 'The entity slot of an ENTITY-scope grant, null for a PLATFORM-scope grant.';
COMMENT ON COLUMN idn_grant_history.entity_uuid IS 'The entity UUID of an ENTITY-scope grant, null for a PLATFORM-scope grant.';
COMMENT ON COLUMN idn_grant_history.issuer_uri IS 'The login system (issuer) whose group the grant names.';
COMMENT ON COLUMN idn_grant_history.external_group IS 'The login system''s group name, exactly as the login system sends it.';
COMMENT ON COLUMN idn_grant_history.role_before IS 'The role before the change, null when the grant was added.';
COMMENT ON COLUMN idn_grant_history.role_after IS 'The role after the change, null when the grant was removed.';
COMMENT ON COLUMN idn_grant_history.change IS 'What happened to the grant: ADDED, REMOVED or CHANGED.';
COMMENT ON COLUMN idn_grant_history.change_source IS 'What made the change: SETUP (the setup command) or APP.';
COMMENT ON COLUMN idn_grant_history.changed_by IS 'Who made the change, as recorded by the setup command or the app.';
COMMENT ON COLUMN idn_grant_history.changed_at IS 'When the change was made.';
COMMENT ON COLUMN idn_grant_history.setup_run_id IS 'The setup run that made the change, set only and always for SETUP changes.';
COMMENT ON COLUMN idn_grant_history.scope IS 'ENTITY for a grant in one entity slot, PLATFORM for a platform-wide grant across all entities.';

-- idn_group_grant
COMMENT ON TABLE idn_group_grant IS '(platform-core) Group-to-role grants: in this entity slot and product, members of this login-system group get this role.';
COMMENT ON COLUMN idn_group_grant.slot_id IS 'The entity slot the grant applies in.';
COMMENT ON COLUMN idn_group_grant.product_code IS 'The product the grant applies to, grants never apply to another product.';
COMMENT ON COLUMN idn_group_grant.issuer_uri IS 'The login system (issuer) the group belongs to.';
COMMENT ON COLUMN idn_group_grant.external_group IS 'The login system''s group name, exactly as the login system sends it.';
COMMENT ON COLUMN idn_group_grant.role IS 'The product role that members of the group get in this entity.';
COMMENT ON COLUMN idn_group_grant.created_at IS 'When the grant was created.';

-- idn_login_connection
COMMENT ON TABLE idn_login_connection IS '(platform-core) Each customer''s login-system connection per product, shared by all its entities, at most one ACTIVE.';
COMMENT ON COLUMN idn_login_connection.connection_id IS 'Unique ID of the login connection.';
COMMENT ON COLUMN idn_login_connection.tenant_id IS 'The customer (tenant ID) this connection belongs to.';
COMMENT ON COLUMN idn_login_connection.product_code IS 'The product this connection signs people in to.';
COMMENT ON COLUMN idn_login_connection.provider_id IS 'Short identifier of the login provider, as given in the setup file, used to find the active connection.';
COMMENT ON COLUMN idn_login_connection.provider_type IS 'Kind of login system: OIDC, SAML or LDAP_AD.';
COMMENT ON COLUMN idn_login_connection.issuer_uri IS 'The login system''s issuer address, which tokens must carry, not unique across customers.';
COMMENT ON COLUMN idn_login_connection.browser_client_id IS 'The client ID the browser uses to sign in, null when not used.';
COMMENT ON COLUMN idn_login_connection.api_audience IS 'The audience that access tokens for the product''s API must carry.';
COMMENT ON COLUMN idn_login_connection.secret_reference IS 'A reference to where a secret is kept (for example a vault path), never the secret itself.';
COMMENT ON COLUMN idn_login_connection.status IS 'Connection status: DRAFT, VALIDATED, ACTIVE or DISABLED.';
COMMENT ON COLUMN idn_login_connection.configuration_version IS 'Version number of the connection''s configuration, raised on each change and kept in the history.';
COMMENT ON COLUMN idn_login_connection.created_at IS 'When the connection was created.';
COMMENT ON COLUMN idn_login_connection.updated_at IS 'When the connection was last changed.';
COMMENT ON COLUMN idn_login_connection.test_sign_in_version IS 'The version of this connection for which a test sign-in with a real token passed; replacing the active connection needs the draft''s current version here (platform-core 1.2.0).';

-- idn_login_connection_history
COMMENT ON TABLE idn_login_connection_history IS '(platform-core) Insert-only record of every version of every login connection, with who changed it.';
COMMENT ON COLUMN idn_login_connection_history.history_id IS 'Generated sequence number of the history row.';
COMMENT ON COLUMN idn_login_connection_history.connection_id IS 'The login connection this version belongs to.';
COMMENT ON COLUMN idn_login_connection_history.configuration_version IS 'The connection''s configuration version recorded in this row.';
COMMENT ON COLUMN idn_login_connection_history.tenant_id IS 'The customer (tenant ID) of the connection at this version.';
COMMENT ON COLUMN idn_login_connection_history.product_code IS 'The product of the connection at this version.';
COMMENT ON COLUMN idn_login_connection_history.provider_id IS 'The login provider identifier at this version.';
COMMENT ON COLUMN idn_login_connection_history.provider_type IS 'The login system kind (OIDC, SAML or LDAP_AD) at this version.';
COMMENT ON COLUMN idn_login_connection_history.issuer_uri IS 'The issuer address at this version.';
COMMENT ON COLUMN idn_login_connection_history.browser_client_id IS 'The browser client ID at this version, null when not used.';
COMMENT ON COLUMN idn_login_connection_history.api_audience IS 'The API audience at this version.';
COMMENT ON COLUMN idn_login_connection_history.secret_reference IS 'The secret reference at this version, never the secret itself.';
COMMENT ON COLUMN idn_login_connection_history.status IS 'The connection status at this version.';
COMMENT ON COLUMN idn_login_connection_history.change_source IS 'What made the change: SETUP, APP or MIGRATION.';
COMMENT ON COLUMN idn_login_connection_history.changed_by IS 'Who made the change, as recorded by the setup command, the app or a migration.';
COMMENT ON COLUMN idn_login_connection_history.changed_at IS 'When this version was recorded.';

-- idn_machine_binding
COMMENT ON TABLE idn_machine_binding IS '(platform-core) Which machine client may deliver to which entity as which sender, per customer and product.';
COMMENT ON COLUMN idn_machine_binding.binding_id IS 'Unique ID of the machine binding.';
COMMENT ON COLUMN idn_machine_binding.tenant_id IS 'The customer (tenant ID) this binding belongs to.';
COMMENT ON COLUMN idn_machine_binding.product_code IS 'The product the machine client delivers to.';
COMMENT ON COLUMN idn_machine_binding.client_id IS 'The machine client''s ID exactly as the login system issues it in the token, compared case-sensitively.';
COMMENT ON COLUMN idn_machine_binding.slot_id IS 'The entity slot the machine client may deliver to.';
COMMENT ON COLUMN idn_machine_binding.entity_uuid IS 'The entity UUID of that slot.';
COMMENT ON COLUMN idn_machine_binding.source_key IS 'Short key naming the sending system (sender) the client acts as.';
COMMENT ON COLUMN idn_machine_binding.description IS 'Optional free-text description of the binding.';
COMMENT ON COLUMN idn_machine_binding.status IS 'ACTIVE or INACTIVE, a binding is switched off rather than deleted.';
COMMENT ON COLUMN idn_machine_binding.version IS 'Version number of the binding, raised on each change and kept in the history.';
COMMENT ON COLUMN idn_machine_binding.created_at IS 'When the binding was created.';
COMMENT ON COLUMN idn_machine_binding.updated_at IS 'When the binding was last changed.';

-- idn_machine_binding_history
COMMENT ON TABLE idn_machine_binding_history IS '(platform-core) Insert-only record of every version of every machine binding, with who changed it.';
COMMENT ON COLUMN idn_machine_binding_history.history_id IS 'Generated sequence number of the history row.';
COMMENT ON COLUMN idn_machine_binding_history.binding_id IS 'The machine binding this version belongs to.';
COMMENT ON COLUMN idn_machine_binding_history.version IS 'The binding''s version recorded in this row.';
COMMENT ON COLUMN idn_machine_binding_history.tenant_id IS 'The customer (tenant ID) of the binding at this version.';
COMMENT ON COLUMN idn_machine_binding_history.product_code IS 'The product of the binding at this version.';
COMMENT ON COLUMN idn_machine_binding_history.client_id IS 'The machine client ID at this version.';
COMMENT ON COLUMN idn_machine_binding_history.slot_id IS 'The entity slot at this version.';
COMMENT ON COLUMN idn_machine_binding_history.source_key IS 'The sender key at this version.';
COMMENT ON COLUMN idn_machine_binding_history.description IS 'The description at this version.';
COMMENT ON COLUMN idn_machine_binding_history.status IS 'The binding status (ACTIVE or INACTIVE) at this version.';
COMMENT ON COLUMN idn_machine_binding_history.change_source IS 'What made the change: SETUP, APP or MIGRATION.';
COMMENT ON COLUMN idn_machine_binding_history.changed_by IS 'Who made the change, as recorded by the setup command, the app or a migration.';
COMMENT ON COLUMN idn_machine_binding_history.changed_at IS 'When this version was recorded.';

-- idn_platform_grant
COMMENT ON TABLE idn_platform_grant IS '(platform-core) Platform-wide grants: for this customer and product, members of this group get this platform role in every entity.';
COMMENT ON COLUMN idn_platform_grant.tenant_id IS 'The customer (tenant ID) the grant belongs to.';
COMMENT ON COLUMN idn_platform_grant.product_code IS 'The product the grant applies to.';
COMMENT ON COLUMN idn_platform_grant.issuer_uri IS 'The login system (issuer) the group belongs to.';
COMMENT ON COLUMN idn_platform_grant.external_group IS 'The login system''s group name, exactly as the login system sends it.';
COMMENT ON COLUMN idn_platform_grant.role IS 'The platform role (one of the product''s platform roles) that members of the group get.';
COMMENT ON COLUMN idn_platform_grant.created_at IS 'When the grant was created.';

-- idn_schema_history
COMMENT ON TABLE idn_schema_history IS '(platform-core) Flyway history of the identity schema migrations (location db/platform/identity).';
COMMENT ON COLUMN idn_schema_history.installed_rank IS 'Order in which the migration ran.';
COMMENT ON COLUMN idn_schema_history.version IS 'Version of the migration, null for repeatable migrations.';
COMMENT ON COLUMN idn_schema_history.description IS 'Description of the migration, taken from its file name.';
COMMENT ON COLUMN idn_schema_history.type IS 'Kind of migration, for example SQL.';
COMMENT ON COLUMN idn_schema_history.script IS 'File name of the migration script.';
COMMENT ON COLUMN idn_schema_history.checksum IS 'Checksum of the script, used to detect a changed migration.';
COMMENT ON COLUMN idn_schema_history.installed_by IS 'Database user that ran the migration.';
COMMENT ON COLUMN idn_schema_history.installed_on IS 'When the migration ran.';
COMMENT ON COLUMN idn_schema_history.execution_time IS 'How long the migration took, in milliseconds.';
COMMENT ON COLUMN idn_schema_history.success IS 'Whether the migration succeeded.';

-- idn_schema_version
COMMENT ON TABLE idn_schema_version IS '(platform-core) One row with the identity schema version, checked by the app at startup.';
COMMENT ON COLUMN idn_schema_version.version IS 'The identity schema version that was installed.';
COMMENT ON COLUMN idn_schema_version.installed_at IS 'When that version was installed.';

-- lic_chain_head
COMMENT ON TABLE lic_chain_head IS '(platform-core) One row holding the newest link of the usage ledger hash chain, moved with every append.';
COMMENT ON COLUMN lic_chain_head.id IS 'Fixed row key, always 1.';
COMMENT ON COLUMN lic_chain_head.sequence IS 'Sequence number of the newest usage record.';
COMMENT ON COLUMN lic_chain_head.hash IS 'Hash of the newest usage record, used to detect removed records.';
COMMENT ON COLUMN lic_chain_head.updated_at IS 'When the chain head last moved.';

-- lic_clock_reset
COMMENT ON TABLE lic_clock_reset IS '(platform-core) Insert-only list of signed last-seen clock resets already applied, so each works only once.';
COMMENT ON COLUMN lic_clock_reset.reset_id IS 'ID of the signed reset that was applied.';
COMMENT ON COLUMN lic_clock_reset.reset_to IS 'The time the latest-seen clock was reset to.';
COMMENT ON COLUMN lic_clock_reset.applied_at IS 'When the reset was applied.';

-- lic_last_seen
COMMENT ON TABLE lic_last_seen IS '(platform-core) One row with the latest time this installation has seen, which only moves forward except by a signed reset.';
COMMENT ON COLUMN lic_last_seen.id IS 'Fixed row key, always 1.';
COMMENT ON COLUMN lic_last_seen.last_seen IS 'The latest time seen, used to detect a clock set back.';
COMMENT ON COLUMN lic_last_seen.updated_at IS 'When the latest time seen was last updated.';

-- lic_licence
COMMENT ON TABLE lic_licence IS '(platform-core) The signed licence in force (CURRENT) and a renewal stored before its start date (WAITING).';
COMMENT ON COLUMN lic_licence.slot IS 'CURRENT for the licence in force or WAITING for a renewal not yet started.';
COMMENT ON COLUMN lic_licence.file IS 'The signed licence file exactly as received, verified on every read.';
COMMENT ON COLUMN lic_licence.licence_id IS 'Copy of the licence ID for people reading the table, never trusted.';
COMMENT ON COLUMN lic_licence.version IS 'Copy of the licence version number, never trusted, also kept as a floor in lic_version_floor.';
COMMENT ON COLUMN lic_licence.tenant_id IS 'Copy of the licence''s tenant ID for people reading the table, never trusted.';
COMMENT ON COLUMN lic_licence.starts_on IS 'Copy of the licence start date for people reading the table, never trusted.';
COMMENT ON COLUMN lic_licence.stored_at IS 'When the licence file was stored.';

-- lic_notice_sent
COMMENT ON TABLE lic_notice_sent IS '(platform-core) Licensing notifications already published, so a restart or a second instance never publishes one twice.';
COMMENT ON COLUMN lic_notice_sent.notice_id IS 'ID naming the notice (event, tenant, slot, licence or month, band), never message content.';
COMMENT ON COLUMN lic_notice_sent.recorded_at IS 'When the notice was recorded as published.';

-- lic_schema_history
COMMENT ON TABLE lic_schema_history IS '(platform-core) Flyway history of the licensing schema migrations (location db/platform/licensing).';
COMMENT ON COLUMN lic_schema_history.installed_rank IS 'Order in which the migration ran.';
COMMENT ON COLUMN lic_schema_history.version IS 'Version of the migration, null for repeatable migrations.';
COMMENT ON COLUMN lic_schema_history.description IS 'Description of the migration, taken from its file name.';
COMMENT ON COLUMN lic_schema_history.type IS 'Kind of migration, for example SQL.';
COMMENT ON COLUMN lic_schema_history.script IS 'File name of the migration script.';
COMMENT ON COLUMN lic_schema_history.checksum IS 'Checksum of the script, used to detect a changed migration.';
COMMENT ON COLUMN lic_schema_history.installed_by IS 'Database user that ran the migration.';
COMMENT ON COLUMN lic_schema_history.installed_on IS 'When the migration ran.';
COMMENT ON COLUMN lic_schema_history.execution_time IS 'How long the migration took, in milliseconds.';
COMMENT ON COLUMN lic_schema_history.success IS 'Whether the migration succeeded.';

-- lic_schema_version
COMMENT ON TABLE lic_schema_version IS '(platform-core) One row with the licensing schema version, checked by the app at startup.';
COMMENT ON COLUMN lic_schema_version.version IS 'The licensing schema version that was installed.';
COMMENT ON COLUMN lic_schema_version.installed_at IS 'When that version was installed.';

-- lic_usage
COMMENT ON TABLE lic_usage IS '(platform-core) Append-only, hash-chained usage ledger of metered units per entity slot, product and month.';
COMMENT ON COLUMN lic_usage.sequence IS 'Position of the record in the chain (1, 2, 3 and so on).';
COMMENT ON COLUMN lic_usage.slot_id IS 'The entity slot the usage is counted for.';
COMMENT ON COLUMN lic_usage.product IS 'The product that recorded the usage.';
COMMENT ON COLUMN lic_usage.type IS 'Allowance type measured: PAGES_PER_MONTH, STORAGE, CONCURRENT_SEATS or NAMED_SEATS.';
COMMENT ON COLUMN lic_usage.period IS 'The month the usage belongs to (YYYY-MM).';
COMMENT ON COLUMN lic_usage.amount IS 'The amount recorded, summed for pages and taken as the highest reading for the other types.';
COMMENT ON COLUMN lic_usage.unit IS 'The unit of the amount, set from the type.';
COMMENT ON COLUMN lic_usage.billable IS 'True when the licence kind is PRODUCTION and the app did not mark the reading free.';
COMMENT ON COLUMN lic_usage.recorded_at IS 'When the usage was recorded.';
COMMENT ON COLUMN lic_usage.previous_hash IS 'Hash of the previous record (64 zeros for the first), linking the chain.';
COMMENT ON COLUMN lic_usage.hash IS 'SHA-256 hash of this record''s other fields, used to detect edits and gaps.';

-- lic_version_floor
COMMENT ON TABLE lic_version_floor IS '(platform-core) The highest licence version ever stored in each slot, kept by the database so an older licence cannot return.';
COMMENT ON COLUMN lic_version_floor.slot IS 'The licence slot, CURRENT or WAITING.';
COMMENT ON COLUMN lic_version_floor.version IS 'The highest licence version ever stored in that slot, never lowered.';

-- erl_position
COMMENT ON TABLE erl_position IS '(platform-core) Applied position of the erase log in this database, one row per log, used to detect a database restore and require an erase replay.';
COMMENT ON COLUMN erl_position.log_id IS 'Id of the erase log (kept outside this database) that this position belongs to, never changed.';
COMMENT ON COLUMN erl_position.applied_seq IS 'Sequence number of the last erase log entry applied in this database (0 means none), never moves back.';
COMMENT ON COLUMN erl_position.applied_hash IS 'Hash (64 hex characters) of the last applied erase log entry, used to check the position against the log.';
COMMENT ON COLUMN erl_position.replay_required IS 'True when the app found this database behind its erase log (a restore), cleared only by a verified erase replay.';
COMMENT ON COLUMN erl_position.restore_detected_at IS 'When the app detected that this database was behind its erase log (a restore).';
COMMENT ON COLUMN erl_position.verified_at IS 'When a verified erase replay last confirmed this position, required to clear replay_required.';
COMMENT ON COLUMN erl_position.updated_at IS 'When this row was last changed.';

-- erl_schema_history
COMMENT ON TABLE erl_schema_history IS '(platform-core) Flyway history of the erase log schema migrations run in this database.';
COMMENT ON COLUMN erl_schema_history.installed_rank IS 'Order in which the migration ran.';
COMMENT ON COLUMN erl_schema_history.version IS 'Migration version, empty for repeatable migrations.';
COMMENT ON COLUMN erl_schema_history.description IS 'Migration description taken from the script name.';
COMMENT ON COLUMN erl_schema_history.type IS 'Migration type, for example SQL.';
COMMENT ON COLUMN erl_schema_history.script IS 'Name of the migration script that ran.';
COMMENT ON COLUMN erl_schema_history.checksum IS 'Checksum of the script, used by Flyway to detect a changed migration.';
COMMENT ON COLUMN erl_schema_history.installed_by IS 'Database user that ran the migration.';
COMMENT ON COLUMN erl_schema_history.installed_on IS 'When the migration ran.';
COMMENT ON COLUMN erl_schema_history.execution_time IS 'How long the migration took, in milliseconds.';
COMMENT ON COLUMN erl_schema_history.success IS 'Whether the migration completed successfully.';

-- erl_schema_version
COMMENT ON TABLE erl_schema_version IS '(platform-core) One row with the version of the erase log schema files, checked by the app at startup.';
COMMENT ON COLUMN erl_schema_version.version IS 'Version of the erase log schema files that ran, the app stops if it is not the expected one.';
COMMENT ON COLUMN erl_schema_version.installed_at IS 'When this schema version was installed.';

-- fld_field_definition
COMMENT ON TABLE fld_field_definition IS '(platform-core) Case field definitions per entity and product, with type, rules and flags, used to validate, store and search field values.';
COMMENT ON COLUMN fld_field_definition.definition_id IS 'Id of the field definition, never changed.';
COMMENT ON COLUMN fld_field_definition.entity_uuid IS 'Entity the field belongs to, never changed.';
COMMENT ON COLUMN fld_field_definition.product_code IS 'Product the field belongs to (for example LEXAVAULT), never changed.';
COMMENT ON COLUMN fld_field_definition.field_key IS 'Key of the field, unique per entity and product ignoring capitals, never changed.';
COMMENT ON COLUMN fld_field_definition.label IS 'Name of the field shown to users.';
COMMENT ON COLUMN fld_field_definition.field_type IS 'Value type of the field, for example TEXT, NUMBER, MONEY, DATE, DROPDOWN, ADDRESS or CUSTOM_PATTERN.';
COMMENT ON COLUMN fld_field_definition.currency IS 'Three-letter currency code, set only for MONEY fields.';
COMMENT ON COLUMN fld_field_definition.options IS 'Allowed choices, set only for DROPDOWN fields.';
COMMENT ON COLUMN fld_field_definition.required IS 'Whether a value must be given for the field.';
COMMENT ON COLUMN fld_field_definition.exact_length IS 'Exact length a value must have, if set (not combined with min or max length).';
COMMENT ON COLUMN fld_field_definition.min_length IS 'Minimum length of a value, if set.';
COMMENT ON COLUMN fld_field_definition.max_length IS 'Maximum length of a value, if set.';
COMMENT ON COLUMN fld_field_definition.pattern IS 'Regular expression a value must match, required for CUSTOM_PATTERN fields.';
COMMENT ON COLUMN fld_field_definition.letter_case IS 'How letters are normalised: PRESERVE, UPPER or LOWER.';
COMMENT ON COLUMN fld_field_definition.strip IS 'Which characters are removed when normalising: NONE, SEPARATORS or NON_DIGITS.';
COMMENT ON COLUMN fld_field_definition.min_value IS 'Lowest allowed value for numeric fields, if set.';
COMMENT ON COLUMN fld_field_definition.max_value IS 'Highest allowed value for numeric fields, if set.';
COMMENT ON COLUMN fld_field_definition.primary_flag IS 'Whether the field is a primary field that identifies a record (one record per primary value).';
COMMENT ON COLUMN fld_field_definition.searchable IS 'Whether records can be searched by this field.';
COMMENT ON COLUMN fld_field_definition.search_mode IS 'How the field is searched: EXACT, PREFIX or PARTIAL (sensitive fields are EXACT only).';
COMMENT ON COLUMN fld_field_definition.sensitive IS 'Whether values are sensitive and stored only sealed and as a keyed fingerprint, never readably.';
COMMENT ON COLUMN fld_field_definition.multi_value IS 'Whether a record may hold several values of this field.';
COMMENT ON COLUMN fld_field_definition.displayable IS 'Whether the field is shown in the app.';
COMMENT ON COLUMN fld_field_definition.display_order IS 'Position of the field when fields are shown.';
COMMENT ON COLUMN fld_field_definition.active IS 'Whether the field is switched on, removing a field means switching it off.';
COMMENT ON COLUMN fld_field_definition.version IS 'Version of the definition, raised with each change.';
COMMENT ON COLUMN fld_field_definition.created_at IS 'When the field was created.';
COMMENT ON COLUMN fld_field_definition.updated_at IS 'When the field was last changed.';
COMMENT ON COLUMN fld_field_definition.updated_by IS 'Who last changed the field.';
COMMENT ON COLUMN fld_field_definition.option_names IS 'Display names of a dropdown field''s values, in the order of options, empty for a value without one (platform-core 1.3.0); only the code is stored, matched and searched.';

-- fld_field_definition_history
COMMENT ON TABLE fld_field_definition_history IS '(platform-core) Every version of every case field definition, written with each change and never changed or deleted.';
COMMENT ON COLUMN fld_field_definition_history.definition_id IS 'Field definition this version belongs to.';
COMMENT ON COLUMN fld_field_definition_history.version IS 'Version number of the definition.';
COMMENT ON COLUMN fld_field_definition_history.change IS 'What happened in this version: CREATED, IMPORTED, CHANGED, SWITCHED_OFF or SWITCHED_ON.';
COMMENT ON COLUMN fld_field_definition_history.changed_at IS 'When the change was made.';
COMMENT ON COLUMN fld_field_definition_history.changed_by IS 'Who made the change.';
COMMENT ON COLUMN fld_field_definition_history.definition IS 'The whole field as it was at this version, as one object of the platform-fields/1 file format.';

-- fld_field_extension
COMMENT ON TABLE fld_field_extension IS '(platform-core) The app''s own named settings per case field (for example can be extracted), outside the shared field model.';
COMMENT ON COLUMN fld_field_extension.definition_id IS 'Field definition the setting belongs to.';
COMMENT ON COLUMN fld_field_extension.name IS 'Name of the app''s setting.';
COMMENT ON COLUMN fld_field_extension.value IS 'Value of the app''s setting.';
COMMENT ON COLUMN fld_field_extension.updated_at IS 'When the setting was last changed.';

-- fld_record
COMMENT ON TABLE fld_record IS '(platform-core) One app record (for example a case''s business record) per entity and product that case field values belong to.';
COMMENT ON COLUMN fld_record.record_uuid IS 'Id of the record, referenced by its field values.';
COMMENT ON COLUMN fld_record.customer_id IS 'Customer the record belongs to.';
COMMENT ON COLUMN fld_record.entity_uuid IS 'Entity the record belongs to.';
COMMENT ON COLUMN fld_record.product_code IS 'Product the record belongs to (for example LEXAVAULT).';
COMMENT ON COLUMN fld_record.record_type IS 'The app''s record type, for example business-record.';
COMMENT ON COLUMN fld_record.record_id IS 'The app''s own id of the record, unique per entity, product and record type.';
COMMENT ON COLUMN fld_record.identity IS 'Hash of the record''s primary field values (never readable sensitive values), so the same primary values lead to the same record.';
COMMENT ON COLUMN fld_record.envelope IS 'The record''s wrapped data key (pck1.), set when the record has sensitive values, used to seal and unseal them.';
COMMENT ON COLUMN fld_record.version IS 'Version of the record, raised with each change of its values.';
COMMENT ON COLUMN fld_record.created_at IS 'When the record was created.';
COMMENT ON COLUMN fld_record.updated_at IS 'When the record was last changed.';

-- fld_schema_history
COMMENT ON TABLE fld_schema_history IS '(platform-core) Flyway history of the case fields schema migrations run in this database.';
COMMENT ON COLUMN fld_schema_history.installed_rank IS 'Order in which the migration ran.';
COMMENT ON COLUMN fld_schema_history.version IS 'Migration version, empty for repeatable migrations.';
COMMENT ON COLUMN fld_schema_history.description IS 'Migration description taken from the script name.';
COMMENT ON COLUMN fld_schema_history.type IS 'Migration type, for example SQL.';
COMMENT ON COLUMN fld_schema_history.script IS 'Name of the migration script that ran.';
COMMENT ON COLUMN fld_schema_history.checksum IS 'Checksum of the script, used by Flyway to detect a changed migration.';
COMMENT ON COLUMN fld_schema_history.installed_by IS 'Database user that ran the migration.';
COMMENT ON COLUMN fld_schema_history.installed_on IS 'When the migration ran.';
COMMENT ON COLUMN fld_schema_history.execution_time IS 'How long the migration took, in milliseconds.';
COMMENT ON COLUMN fld_schema_history.success IS 'Whether the migration completed successfully.';

-- fld_schema_version
COMMENT ON TABLE fld_schema_version IS '(platform-core) One row with the version of the case fields schema files, checked by the app at startup.';
COMMENT ON COLUMN fld_schema_version.version IS 'Version of the case fields schema files that ran, the app stops if it is not the expected one.';
COMMENT ON COLUMN fld_schema_version.installed_at IS 'When this schema version was installed.';

-- fld_value
COMMENT ON TABLE fld_value IS '(platform-core) One value of one case field of a record, readable if not sensitive, otherwise only sealed and fingerprinted, used for display and search.';
COMMENT ON COLUMN fld_value.record_uuid IS 'Record the value belongs to.';
COMMENT ON COLUMN fld_value.customer_id IS 'Customer of the record, repeated for search scope.';
COMMENT ON COLUMN fld_value.entity_uuid IS 'Entity of the record, repeated for the search indexes.';
COMMENT ON COLUMN fld_value.product_code IS 'Product of the record, repeated for the search indexes.';
COMMENT ON COLUMN fld_value.record_type IS 'Record type of the record, repeated for the search indexes.';
COMMENT ON COLUMN fld_value.field_key IS 'Key of the field this value is for.';
COMMENT ON COLUMN fld_value.position IS 'Position of the value within a multi-value field (0 to 49), 0 for a single value.';
COMMENT ON COLUMN fld_value.sensitive IS 'Whether the value is sensitive, in which case only sealed_value and fingerprint are set.';
COMMENT ON COLUMN fld_value.display_value IS 'The value as given, for display, empty for sensitive values.';
COMMENT ON COLUMN fld_value.normalised_value IS 'The value after normalisation (address parts separated by U+001F), empty for sensitive values.';
COMMENT ON COLUMN fld_value.search_value IS 'Normalised value in lower case with single spaces, used for search and the primary rule, empty for sensitive values.';
COMMENT ON COLUMN fld_value.sealed_value IS 'Sensitive value sealed (pci1.) under the record''s envelope, empty for values that are not sensitive.';
COMMENT ON COLUMN fld_value.fingerprint IS 'Keyed fingerprint (HMAC) of a sensitive searchable or primary value, used for exact search without revealing it.';

-- notif_approval_request
COMMENT ON TABLE notif_approval_request IS '(platform-core) Requests waiting for a decision through one-click links, and the first decision made, used for approvals such as delivery approval.';
COMMENT ON COLUMN notif_approval_request.tenant_id IS 'Tenant the request belongs to.';
COMMENT ON COLUMN notif_approval_request.request_id IS 'Id of the request within the tenant, named by the signed one-click links.';
COMMENT ON COLUMN notif_approval_request.entity_id IS 'Entity the request belongs to.';
COMMENT ON COLUMN notif_approval_request.request_type IS 'Kind of request (for example a delivery approval), whose type rules say who decides and what happens on expiry (to confirm).';
COMMENT ON COLUMN notif_approval_request.requester IS 'User id of the person who asked, who may not decide their own request.';
COMMENT ON COLUMN notif_approval_request.identifier IS 'Record identifier the request is about (for example a case number), shown in messages.';
COMMENT ON COLUMN notif_approval_request.link IS 'Link into the app for the request.';
COMMENT ON COLUMN notif_approval_request.created_at IS 'When the request was made.';
COMMENT ON COLUMN notif_approval_request.expires_at IS 'When the request expires if not decided.';
COMMENT ON COLUMN notif_approval_request.decision IS 'The decision, APPROVED or DECLINED, empty while waiting, only the first decision is stored.';
COMMENT ON COLUMN notif_approval_request.decided_by IS 'User id of the person who decided.';
COMMENT ON COLUMN notif_approval_request.decided_by_name IS 'Display name of the person who decided.';
COMMENT ON COLUMN notif_approval_request.decided_at IS 'When the decision was made.';
COMMENT ON COLUMN notif_approval_request.reason IS 'Decider''s own reason, required for a decline, shown on the app''s page and never put in a message or log.';
COMMENT ON COLUMN notif_approval_request.expiry_told_at IS 'When the requester was told the request expired undecided, if its type says so.';

-- notif_condition
COMMENT ON TABLE notif_condition IS '(platform-core) Continuous problem conditions that are going on, used to alert once after a threshold and repeat, not per failure.';
COMMENT ON COLUMN notif_condition.tenant_id IS 'Tenant the condition belongs to.';
COMMENT ON COLUMN notif_condition.event_type IS 'Event type of the condition.';
COMMENT ON COLUMN notif_condition.entity_id IS 'Entity the condition concerns, empty text when it has none.';
COMMENT ON COLUMN notif_condition.identifier IS 'Record identifier the condition concerns, empty text when it has none.';
COMMENT ON COLUMN notif_condition.since IS 'When the condition started (its first failing report).';
COMMENT ON COLUMN notif_condition.failures IS 'Number of failing reports seen so far.';
COMMENT ON COLUMN notif_condition.alerts IS 'Number of alerts sent so far (first alert and repeats), 0 until the threshold passes.';
COMMENT ON COLUMN notif_condition.last_alert_at IS 'When the last alert was raised, empty while no alert was sent.';
COMMENT ON COLUMN notif_condition.counts IS 'Counts of the latest report, as name=value pairs.';
COMMENT ON COLUMN notif_condition.times IS 'Times of the latest report, as name=instant pairs.';
COMMENT ON COLUMN notif_condition.link IS 'Link into the app from the latest report.';
COMMENT ON COLUMN notif_condition.occurred_at IS 'When the latest failing report happened.';
COMMENT ON COLUMN notif_condition.updated_at IS 'When this row was last changed.';

-- notif_destination
COMMENT ON TABLE notif_destination IS '(platform-core) Message channels (e-mail, Teams, Slack) an IT admin connected for a tenant, referenced by id, their secrets kept in the secret store.';
COMMENT ON COLUMN notif_destination.tenant_id IS 'Tenant the destination belongs to.';
COMMENT ON COLUMN notif_destination.destination_id IS 'Id of the destination within the tenant, its addresses and secrets are kept in the secret store.';
COMMENT ON COLUMN notif_destination.channel IS 'Kind of channel: EMAIL, TEAMS or SLACK.';

-- notif_event_settings
COMMENT ON TABLE notif_event_settings IS '(platform-core) Admins'' notification choices per event type, technical ones per tenant and business ones per entity.';
COMMENT ON COLUMN notif_event_settings.tenant_id IS 'Tenant the settings belong to.';
COMMENT ON COLUMN notif_event_settings.level IS 'TECHNICAL (per tenant) or BUSINESS (per entity).';
COMMENT ON COLUMN notif_event_settings.entity_id IS 'Entity for business settings, empty text for technical settings.';
COMMENT ON COLUMN notif_event_settings.event_type IS 'Event type the settings are for.';
COMMENT ON COLUMN notif_event_settings.enabled IS 'Whether notifications for this event type are switched on.';
COMMENT ON COLUMN notif_event_settings.frequency IS 'How often to send: IMMEDIATELY, EVERY_HOUR, EVERY_4_HOURS or ONCE_A_DAY (summary).';
COMMENT ON COLUMN notif_event_settings.daily_at IS 'Time of day for the daily summary, set only for ONCE_A_DAY.';
COMMENT ON COLUMN notif_event_settings.recipient_roles IS 'Roles whose members receive the messages, looked up at send time.';
COMMENT ON COLUMN notif_event_settings.destinations IS 'Ids of the destinations the messages go to.';
COMMENT ON COLUMN notif_event_settings.alert_after_seconds IS 'For continuous conditions, how long the condition must last before the first alert.';
COMMENT ON COLUMN notif_event_settings.repeat_every_seconds IS 'For continuous conditions, how often to repeat the alert while it lasts.';
COMMENT ON COLUMN notif_event_settings.thresholds IS 'Numeric thresholds for the event type, as name=value pairs.';
COMMENT ON COLUMN notif_event_settings.updated_at IS 'When the settings were last changed.';

-- notif_outbox
COMMENT ON TABLE notif_outbox IS '(platform-core) Every outgoing notification message, saved before it is sent and retried until sent or failed.';
COMMENT ON COLUMN notif_outbox.message_key IS 'Idempotency key of the message, so the same message is never saved or sent twice.';
COMMENT ON COLUMN notif_outbox.seq IS 'Insertion order, first in first out among messages due together.';
COMMENT ON COLUMN notif_outbox.tenant_id IS 'Tenant the message belongs to.';
COMMENT ON COLUMN notif_outbox.destination_id IS 'Destination the message is sent to.';
COMMENT ON COLUMN notif_outbox.channel IS 'Kind of channel: EMAIL, TEAMS or SLACK.';
COMMENT ON COLUMN notif_outbox.entity_id IS 'Entity the message concerns, if any.';
COMMENT ON COLUMN notif_outbox.recipient_roles IS 'Roles whose members receive the message, resolved to addresses at send time.';
COMMENT ON COLUMN notif_outbox.event_types IS 'Event types the message reports.';
COMMENT ON COLUMN notif_outbox.message_kind IS 'What the message says: EVENT, CONDITION_ALERT, CONDITION_ONGOING, RECOVERED or SUMMARY.';
COMMENT ON COLUMN notif_outbox.subject IS 'Subject line of the message.';
COMMENT ON COLUMN notif_outbox.body_lines IS 'Lines of the message body (event, identifier, counts, times, never document content).';
COMMENT ON COLUMN notif_outbox.link IS 'Link into the app included in the message.';
COMMENT ON COLUMN notif_outbox.state IS 'Delivery state: PENDING, SENDING, SENT or FAILED.';
COMMENT ON COLUMN notif_outbox.attempts IS 'Number of send attempts so far.';
COMMENT ON COLUMN notif_outbox.created_at IS 'When the message was saved.';
COMMENT ON COLUMN notif_outbox.next_attempt_at IS 'When the next send attempt is due.';
COMMENT ON COLUMN notif_outbox.claimed_until IS 'Until when a sender has claimed the message for sending.';
COMMENT ON COLUMN notif_outbox.last_failure IS 'Code of the last send failure.';
COMMENT ON COLUMN notif_outbox.finished_at IS 'When the message was finally sent or failed, used to clear out old messages.';
COMMENT ON COLUMN notif_outbox.action_request IS 'Approval request a decision message names, its one-click links are made at send time and never stored.';
COMMENT ON COLUMN notif_outbox.recipient_user IS 'User id of the single person an e-mail goes to (for example the requester), never their address.';

-- notif_schema_history
COMMENT ON TABLE notif_schema_history IS '(platform-core) Flyway history of the notifications schema migrations run in this database.';
COMMENT ON COLUMN notif_schema_history.installed_rank IS 'Order in which the migration ran.';
COMMENT ON COLUMN notif_schema_history.version IS 'Migration version, empty for repeatable migrations.';
COMMENT ON COLUMN notif_schema_history.description IS 'Migration description taken from the script name.';
COMMENT ON COLUMN notif_schema_history.type IS 'Migration type, for example SQL.';
COMMENT ON COLUMN notif_schema_history.script IS 'Name of the migration script that ran.';
COMMENT ON COLUMN notif_schema_history.checksum IS 'Checksum of the script, used by Flyway to detect a changed migration.';
COMMENT ON COLUMN notif_schema_history.installed_by IS 'Database user that ran the migration.';
COMMENT ON COLUMN notif_schema_history.installed_on IS 'When the migration ran.';
COMMENT ON COLUMN notif_schema_history.execution_time IS 'How long the migration took, in milliseconds.';
COMMENT ON COLUMN notif_schema_history.success IS 'Whether the migration completed successfully.';

-- notif_schema_version
COMMENT ON TABLE notif_schema_version IS '(platform-core) One row with the version of the notifications schema files, checked by the app at startup.';
COMMENT ON COLUMN notif_schema_version.version IS 'Version of the notifications schema files that ran, the app stops if it is not the expected one.';
COMMENT ON COLUMN notif_schema_version.installed_at IS 'When this schema version was installed.';

-- notif_summary_item
COMMENT ON TABLE notif_summary_item IS '(platform-core) Event occurrences waiting to be sent in a scheduled summary message for a destination.';
COMMENT ON COLUMN notif_summary_item.item_key IS 'Key unique per occurrence and destination, so adding the same item twice keeps one.';
COMMENT ON COLUMN notif_summary_item.seq IS 'Insertion order, used to list items in a summary in order.';
COMMENT ON COLUMN notif_summary_item.tenant_id IS 'Tenant the item belongs to.';
COMMENT ON COLUMN notif_summary_item.destination_id IS 'Destination the summary goes to.';
COMMENT ON COLUMN notif_summary_item.frequency IS 'Summary schedule: EVERY_HOUR, EVERY_4_HOURS or ONCE_A_DAY.';
COMMENT ON COLUMN notif_summary_item.daily_at IS 'Time of day of the daily summary, set only for ONCE_A_DAY.';
COMMENT ON COLUMN notif_summary_item.entity_id IS 'Entity the item concerns, if any.';
COMMENT ON COLUMN notif_summary_item.recipient_roles IS 'Roles whose members receive the summary.';
COMMENT ON COLUMN notif_summary_item.due_at IS 'When the summary holding this item is due to be sent.';
COMMENT ON COLUMN notif_summary_item.event_type IS 'Event type of the occurrence.';
COMMENT ON COLUMN notif_summary_item.message_kind IS 'What the occurrence says: EVENT, CONDITION_ALERT, CONDITION_ONGOING or RECOVERED.';
COMMENT ON COLUMN notif_summary_item.identifier IS 'Record identifier the occurrence concerns, if any.';
COMMENT ON COLUMN notif_summary_item.counts IS 'Counts of the occurrence, as name=value pairs.';
COMMENT ON COLUMN notif_summary_item.times IS 'Times of the occurrence, as name=instant pairs.';
COMMENT ON COLUMN notif_summary_item.link IS 'Link into the app for the occurrence.';
COMMENT ON COLUMN notif_summary_item.occurred_at IS 'When the reported event happened, if known.';
COMMENT ON COLUMN notif_summary_item.raised_at IS 'When the event happened or the alert was raised.';
COMMENT ON COLUMN notif_summary_item.since IS 'When the condition started, empty for one-off events.';
COMMENT ON COLUMN notif_summary_item.failures IS 'Failing reports seen for the condition, 0 for one-off events.';

-- notif_tenant
COMMENT ON TABLE notif_tenant IS '(platform-core) Notification settings per tenant (app link base and time zone) that its destinations belong to.';
COMMENT ON COLUMN notif_tenant.tenant_id IS 'Tenant the settings belong to.';
COMMENT ON COLUMN notif_tenant.app_base_url IS 'Base address of the app, used to build links in messages.';
COMMENT ON COLUMN notif_tenant.zone_id IS 'Tenant''s time zone, for daily summaries and the times shown in messages.';
COMMENT ON COLUMN notif_tenant.updated_at IS 'When the settings were last changed.';
