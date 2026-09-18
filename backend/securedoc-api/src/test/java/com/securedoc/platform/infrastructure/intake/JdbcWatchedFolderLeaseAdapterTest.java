package com.securedoc.platform.infrastructure.intake;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.Duration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class JdbcWatchedFolderLeaseAdapterTest {

    @Test
    void gracefulReleaseExpiresLeaseWithoutDeletingFencingHistory() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        var adapter = new JdbcWatchedFolderLeaseAdapter(jdbc);

        assertTrue(adapter.release("watched-folder-intake", "APP-01/process-a", 7L));

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        var params = org.mockito.ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(sql.capture(), params.capture());

        String statement = sql.getValue();
        assertTrue(statement.contains("UPDATE watched_folder_leases"));
        assertTrue(statement.contains("lease_expires_at = clock_timestamp()"));
        assertFalse(statement.contains("DELETE FROM watched_folder_leases"));
        assertEquals("watched-folder-intake", params.getValue().getValue("leaseName"));
        assertEquals("APP-01/process-a", params.getValue().getValue("ownerId"));
        assertEquals(7L, params.getValue().getValue("fencingToken"));
    }

    @Test
    void coordinationTimeComesFromPostgresClock() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        JdbcTemplate plain = mock(JdbcTemplate.class);
        when(jdbc.getJdbcOperations()).thenReturn(plain);
        Instant expected = Instant.parse("2026-09-09T16:00:00Z");
        when(plain.queryForObject("SELECT clock_timestamp()", Timestamp.class)).thenReturn(Timestamp.from(expected));
        var adapter = new JdbcWatchedFolderLeaseAdapter(jdbc);

        assertEquals(expected, adapter.currentTime());
        verify(plain).queryForObject("SELECT clock_timestamp()", Timestamp.class);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void acquisitionSqlAdvancesFencingGenerationWhenOwnershipChanges() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        var adapter = new JdbcWatchedFolderLeaseAdapter(jdbc);

        assertTrue(adapter.acquireOrRenew(
                "watched-folder-intake", "APP-02/process-b", Duration.ofSeconds(10)).isEmpty());

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));

        String statement = sql.getValue();
        assertTrue(statement.contains("watched_folder_leases.fencing_token + 1"));
        assertTrue(statement.contains("watched_folder_leases.lease_expires_at <= clock_timestamp()"));
    }
}
