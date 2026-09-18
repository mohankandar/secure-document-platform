SELECT
    owner_id,
    fencing_token,
    heartbeat_at,
    lease_expires_at,
    now() AS db_now,
    lease_expires_at > now() AS lease_valid
FROM watched_folder_leases;