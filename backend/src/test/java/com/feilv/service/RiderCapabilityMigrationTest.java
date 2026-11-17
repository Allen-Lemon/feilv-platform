package com.feilv.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 仅在内存H2执行迁移内的真实DDL；MySQL的条件PREPARE包装按元数据守卫核对，不连接部署数据库。 */
class RiderCapabilityMigrationTest {
    private static final String MIGRATION = "db/migrations/20261117_rider_service_capabilities.sql";
    private static final Pattern ALTER = Pattern.compile("'((?:ALTER TABLE)(?:''|[^'])+)'", Pattern.MULTILINE);
    private static final Pattern ADD = Pattern.compile("(?i)^ALTER\\s+TABLE\\s+(\\w+)\\s+ADD\\s+COLUMN\\s+(\\w+)\\s+.+$");

    private String resource(String name) throws Exception {
        return new ClassPathResource(name).getContentAsString(StandardCharsets.UTF_8);
    }

    /** 执行与information_schema条件相同的存在性检查；存在时跳过，不能把ADD改写成更新旧行。 */
    private int apply(Connection connection, String raw) throws SQLException {
        int executed = 0, found = 0;
        Matcher statements = ALTER.matcher(raw);
        try (Statement statement = connection.createStatement()) {
            while (statements.find()) {
                found++;
                String ddl = statements.group(1).replace("''", "'"); Matcher add = ADD.matcher(ddl);
                assertTrue(add.matches(), "能力迁移仅允许添加列: " + ddl);
                String table = add.group(1).toUpperCase(Locale.ROOT), column = add.group(2).toUpperCase(Locale.ROOT);
                try (var metadata = statement.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME='" + table + "' AND COLUMN_NAME='" + column + "'")) {
                    assertTrue(metadata.next()); if (metadata.getInt(1) > 0) continue;
                }
                statement.execute(ddl); executed++;
            }
        }
        assertEquals(2, found, "必须覆盖接送及代驾两条真实DDL"); return executed;
    }

    @Test void historicalRidersReceiveFalseDefaultsAndRepeatUpgradePreservesAuthorizationsAndFinancialHistory() throws Exception {
        String raw = resource(MIGRATION);
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:rider-capability-migration;MODE=MySQL", "sa", ""); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE t_rider(id BIGINT PRIMARY KEY,user_id BIGINT,name VARCHAR(32),vehicle_type VARCHAR(16),status VARCHAR(16),online_status INT,credit_score INT)");
            statement.execute("INSERT INTO t_rider VALUES(1,101,'历史汽车骑手','CAR','ACTIVE',1,125),(2,102,'历史电动车骑手','E-BIKE','ACTIVE',2,100),(3,103,'冻结骑手','CAR','FROZEN',0,50)");
            statement.execute("CREATE TABLE t_order(id BIGINT PRIMARY KEY,order_type VARCHAR(16),status VARCHAR(16),rider_id BIGINT,estimate_fee INT,real_fee INT)");
            statement.execute("CREATE TABLE t_pay_record(id BIGINT PRIMARY KEY,order_id BIGINT,amount INT,share_rider INT,share_platform INT)");
            statement.execute("CREATE TABLE t_wallet_order_settlement(id BIGINT PRIMARY KEY,order_id BIGINT,rider_id BIGINT,gross_fen INT,net_fen INT)");
            statement.execute("CREATE TABLE t_wallet_account(user_id BIGINT PRIMARY KEY,available_fen INT)");
            statement.execute("INSERT INTO t_order VALUES(9,'CUSTOM','FINISHED',1,1234,1234)");
            statement.execute("INSERT INTO t_pay_record VALUES(19,9,1234,864,370)");
            statement.execute("INSERT INTO t_wallet_order_settlement VALUES(29,9,1,864,864)");
            statement.execute("INSERT INTO t_wallet_account VALUES(101,864)");
            assertEquals(2, apply(connection, raw));
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM t_rider WHERE ride_enabled=0 AND driving_enabled=0")) {
                assertTrue(result.next()); assertEquals(3, result.getInt(1), "车辆类型、信用分、在线状态不得触发自动授权");
            }
            statement.execute("UPDATE t_rider SET ride_enabled=1 WHERE id=1");
            statement.execute("UPDATE t_rider SET driving_enabled=1 WHERE id=2");
            assertEquals(0, apply(connection, raw)); assertEquals(0, apply(connection, raw));
            try (var result = statement.executeQuery("SELECT user_id,name,vehicle_type,status,online_status,credit_score,ride_enabled,driving_enabled FROM t_rider WHERE id=1")) {
                assertTrue(result.next()); assertEquals(101, result.getLong(1)); assertEquals("历史汽车骑手", result.getString(2));
                assertEquals("CAR", result.getString(3)); assertEquals("ACTIVE", result.getString(4)); assertEquals(1, result.getInt(5));
                assertEquals(125, result.getInt(6)); assertTrue(result.getBoolean(7)); assertFalse(result.getBoolean(8));
            }
            try (var result = statement.executeQuery("SELECT ride_enabled,driving_enabled FROM t_rider WHERE id=2")) {
                assertTrue(result.next()); assertFalse(result.getBoolean(1)); assertTrue(result.getBoolean(2));
            }
            statement.execute("INSERT INTO t_rider(id,user_id,name,vehicle_type,status,online_status,credit_score) VALUES(4,104,'升级后新骑手','CAR','ACTIVE',1,100)");
            try (var result = statement.executeQuery("SELECT ride_enabled,driving_enabled FROM t_rider WHERE id=4")) {
                assertTrue(result.next()); assertFalse(result.getBoolean(1)); assertFalse(result.getBoolean(2));
                assertFalse(result.wasNull());
            }
            assertThrows(SQLException.class, () -> statement.execute("UPDATE t_rider SET ride_enabled=NULL WHERE id=4"));
            assertThrows(SQLException.class, () -> statement.execute("UPDATE t_rider SET driving_enabled=NULL WHERE id=4"));
            try (var result = statement.executeQuery("SELECT order_type,status,rider_id,estimate_fee,real_fee FROM t_order WHERE id=9")) {
                assertTrue(result.next()); assertEquals("CUSTOM", result.getString(1)); assertEquals("FINISHED", result.getString(2));
                assertEquals(1, result.getLong(3)); assertEquals(1234, result.getInt(4)); assertEquals(1234, result.getInt(5));
            }
            try (var result = statement.executeQuery("SELECT amount,share_rider,share_platform FROM t_pay_record WHERE id=19")) {
                assertTrue(result.next()); assertEquals(1234, result.getInt(1)); assertEquals(864, result.getInt(2)); assertEquals(370, result.getInt(3));
            }
            try (var result = statement.executeQuery("SELECT rider_id,gross_fen,net_fen FROM t_wallet_order_settlement WHERE id=29")) {
                assertTrue(result.next()); assertEquals(1, result.getLong(1)); assertEquals(864, result.getInt(2)); assertEquals(864, result.getInt(3));
            }
            try (var result = statement.executeQuery("SELECT available_fen FROM t_wallet_account WHERE user_id=101")) {
                assertTrue(result.next()); assertEquals(864, result.getInt(1));
            }
        }
    }

    @Test void interruptedOneColumnUpgradeAddsOnlyMissingColumnAndDoesNotResetExistingGrant() throws Exception {
        String raw = resource(MIGRATION);
        for (String existing : List.of("ride_enabled", "driving_enabled")) {
            try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:rider-capability-partial-" + existing + ";MODE=MySQL", "sa", ""); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t_rider(id BIGINT PRIMARY KEY," + existing + " TINYINT NOT NULL DEFAULT 0)");
                statement.execute("INSERT INTO t_rider VALUES(1,1)");
                assertEquals(1, apply(connection, raw)); assertEquals(0, apply(connection, raw));
                try (var result = statement.executeQuery("SELECT ride_enabled,driving_enabled FROM t_rider WHERE id=1")) {
                    assertTrue(result.next()); assertEquals("ride_enabled".equals(existing), result.getBoolean(1));
                    assertEquals("driving_enabled".equals(existing), result.getBoolean(2));
                }
            }
        }
    }

    @Test void mysqlConditionalGuardsSchemaAndAllDeploymentMigrationListsStayAligned() throws Exception {
        String raw = resource(MIGRATION), normalized = raw.toLowerCase(Locale.ROOT);
        assertEquals(2, normalized.split("information_schema.columns", -1).length - 1);
        assertEquals(2, normalized.split("table_schema=database()", -1).length - 1);
        assertEquals(2, normalized.split("table_name='t_rider'", -1).length - 1);
        assertEquals(2, Pattern.compile("(?i)\\bSELECT\\s+IF\\(COUNT\\(\\*\\)=0,").matcher(raw).results().count());
        assertEquals(2, Pattern.compile("(?im)^PREPARE\\s+").matcher(raw).results().count());
        assertEquals(2, Pattern.compile("(?im)^EXECUTE\\s+").matcher(raw).results().count());
        assertEquals(2, Pattern.compile("(?im)^DEALLOCATE\\s+PREPARE\\s+").matcher(raw).results().count());
        for (String column : List.of("ride_enabled", "driving_enabled")) {
            assertTrue(normalized.contains("column_name='" + column + "'"));
            assertTrue(Pattern.compile("(?i)ALTER\\s+TABLE\\s+t_rider\\s+ADD\\s+COLUMN\\s+" + column + "\\s+TINYINT\\s+NOT\\s+NULL\\s+DEFAULT\\s+0").matcher(raw).find());
        }
        assertFalse(Pattern.compile("(?i)\\b(?:UPDATE|DELETE|INSERT|DROP|TRUNCATE|RENAME)\\s+").matcher(raw).find(), "能力增量只能补列，不得改写旧行、订单或账务");
        for (String profile : List.of("application.yml", "application-local.yml", "application-docker.yml")) {
            assertEquals(1, resource(profile).split(Pattern.quote("classpath:" + MIGRATION), -1).length - 1, profile);
        }
        String schema = resource("db/schema.sql");
        for (String column : List.of("ride_enabled", "driving_enabled")) {
            assertTrue(Pattern.compile("(?im)^\\s*" + column + "\\s+TINYINT\\s+NOT\\s+NULL\\s+DEFAULT\\s+0\\s*,").matcher(schema).find(), column);
        }
    }
}
