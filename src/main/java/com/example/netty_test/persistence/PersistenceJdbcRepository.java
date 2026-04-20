package com.example.netty_test.persistence;

import com.example.netty_test.metrics.RobotMetrics;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

@Repository
public class PersistenceJdbcRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RobotMetrics robotMetrics;

    public PersistenceJdbcRepository(JdbcTemplate jdbcTemplate, RobotMetrics robotMetrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.robotMetrics = robotMetrics;
    }

    public void saveStatuses(List<StatusPersistenceCommand> commands) {
        if (commands.isEmpty()) {
            return;
        }

        long startedAt = System.nanoTime();
        try {
            // hot path에서는 JPA entity 생성/dirty checking 대신 JDBC batch insert만 수행한다.
            jdbcTemplate.batchUpdate(
                    """
                    insert into robot_status_history (
                        robot_type, robot_id, occurred_at, status_code, battery_percent
                    ) values (?, ?, ?, ?, ?)
                    """,
                    new BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(PreparedStatement ps, int i) throws SQLException {
                            StatusPersistenceCommand command = commands.get(i);
                            ps.setInt(1, Byte.toUnsignedInt(command.robotType()));
                            ps.setString(2, command.robotId());
                            ps.setTimestamp(3, Timestamp.from(command.occurredAt()));
                            ps.setInt(4, command.statusCode());
                            ps.setInt(5, command.batteryPercent());
                        }

                        @Override
                        public int getBatchSize() {
                            return commands.size();
                        }
                    }
            );
            robotMetrics.recordBatchInsert("status", commands.size(), System.nanoTime() - startedAt, true);
        } catch (RuntimeException e) {
            robotMetrics.recordBatchInsert("status", commands.size(), System.nanoTime() - startedAt, false);
            robotMetrics.incrementPersistenceFailure("status");
            // 본 테이블 적재 실패 시 최소한의 장애 분석 정보는 failure 테이블에 남긴다.
            saveFailures(commands.stream()
                    .map(command -> new FailurePersistenceRecord(command.robotType(), command.opCode(), command.robotId(), e.getMessage()))
                    .toList()
            );
            throw e;
        }
    }

    public void savePositions(List<PositionPersistenceCommand> commands) {
        if (commands.isEmpty()) {
            return;
        }

        long startedAt = System.nanoTime();
        try {
            jdbcTemplate.batchUpdate(
                    """
                    insert into robot_position_history (
                        robot_type, robot_id, occurred_at, pos_x, pos_y, heading_deg
                    ) values (?, ?, ?, ?, ?, ?)
                    """,
                    new BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(PreparedStatement ps, int i) throws SQLException {
                            PositionPersistenceCommand command = commands.get(i);
                            ps.setInt(1, Byte.toUnsignedInt(command.robotType()));
                            ps.setString(2, command.robotId());
                            ps.setTimestamp(3, Timestamp.from(command.occurredAt()));
                            ps.setDouble(4, command.x());
                            ps.setDouble(5, command.y());
                            ps.setFloat(6, command.headingDeg());
                        }

                        @Override
                        public int getBatchSize() {
                            return commands.size();
                        }
                    }
            );
            robotMetrics.recordBatchInsert("position", commands.size(), System.nanoTime() - startedAt, true);
        } catch (RuntimeException e) {
            robotMetrics.recordBatchInsert("position", commands.size(), System.nanoTime() - startedAt, false);
            robotMetrics.incrementPersistenceFailure("position");
            saveFailures(commands.stream()
                    .map(command -> new FailurePersistenceRecord(command.robotType(), command.opCode(), command.robotId(), e.getMessage()))
                    .toList()
            );
            throw e;
        }
    }

    public void saveFailures(List<FailurePersistenceRecord> failures) {
        if (failures.isEmpty()) {
            return;
        }

        try {
            // 장애 기록 저장까지 또 실패하더라도 메인 워커를 멈추진 않는다.
            jdbcTemplate.batchUpdate(
                    """
                    insert into robot_ingest_failure (
                        robot_type, op_code, robot_id, error_message
                    ) values (?, ?, ?, ?)
                    """,
                    new BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(PreparedStatement ps, int i) throws SQLException {
                            FailurePersistenceRecord failure = failures.get(i);
                            ps.setInt(1, Byte.toUnsignedInt(failure.robotType()));
                            ps.setInt(2, Byte.toUnsignedInt(failure.opCode()));
                            ps.setString(3, failure.robotId());
                            ps.setString(4, failure.errorMessage());
                        }

                        @Override
                        public int getBatchSize() {
                            return failures.size();
                        }
                    }
            );
        } catch (RuntimeException ignored) {
            robotMetrics.incrementPersistenceFailure("failure_table");
        }
    }
}
