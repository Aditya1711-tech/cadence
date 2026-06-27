package com.cadence.query;

import com.cadence.event.EventDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Maps an {@code events} row to the Event Contract {@link EventDto}. */
public class EventRowMapper implements RowMapper<EventDto> {

    private final ObjectMapper mapper;

    public EventRowMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public EventDto mapRow(ResultSet rs, int rowNum) throws SQLException {
        JsonNode meta;
        try {
            String raw = rs.getString("meta");
            meta = raw == null ? null : mapper.readTree(raw);
        } catch (Exception e) {
            meta = null;
        }
        return new EventDto(
                rs.getObject("event_id", UUID.class),
                rs.getInt("schema_ver"),
                rs.getString("source"),
                rs.getObject("member_id", UUID.class),
                toOdt(rs, "ts_start"),
                toOdt(rs, "ts_end"),
                rs.getLong("duration_ms"),
                rs.getString("app"),
                rs.getString("title"),
                rs.getString("url"),
                rs.getString("project"),
                rs.getString("category"),
                rs.getBoolean("is_idle"),
                meta);
    }

    private static OffsetDateTime toOdt(ResultSet rs, String col) throws SQLException {
        var ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }
}
