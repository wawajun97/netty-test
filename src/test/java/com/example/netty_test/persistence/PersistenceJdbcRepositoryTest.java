package com.example.netty_test.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class PersistenceJdbcRepositoryTest {
    @Autowired
    private PersistenceJdbcRepository persistenceJdbcRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from robot_status_history");
        jdbcTemplate.update("delete from robot_position_history");
        jdbcTemplate.update("delete from robot_ingest_failure");
    }

    @Test
    void savesStatusBatch() {
        persistenceJdbcRepository.saveStatuses(List.of(
                new StatusPersistenceCommand((byte) 0x01, "robot-a", Instant.now(), 1, 95),
                new StatusPersistenceCommand((byte) 0x02, "robot-b", Instant.now(), 2, 50)
        ));

        Integer count = jdbcTemplate.queryForObject("select count(*) from robot_status_history", Integer.class);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void savesPositionBatch() {
        persistenceJdbcRepository.savePositions(List.of(
                new PositionPersistenceCommand((byte) 0x01, "robot-a", Instant.now(), 1.0, 2.0, 90.0f)
        ));

        Integer count = jdbcTemplate.queryForObject("select count(*) from robot_position_history", Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
