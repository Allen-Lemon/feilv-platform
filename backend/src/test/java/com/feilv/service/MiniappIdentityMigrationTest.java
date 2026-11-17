package com.feilv.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ByteArrayResource;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.List;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Memory-only execution; migration must remain additive and repeatable, without an account merge. */
class MiniappIdentityMigrationTest {
    private static final String MIGRATION = "db/migrations/20261117_dual_miniapp_identity.sql";
    private String resource(String name) throws Exception { return new ClassPathResource(name).getContentAsString(StandardCharsets.UTF_8); }

    @Test void repeatedMigrationAddsOnlyProofTablesAndPreservesAllExistingBusinessIdsAndBalances() throws Exception {
        String raw = resource(MIGRATION);
        assertFalse(Pattern.compile("(?im)^\\s*(UPDATE|DELETE|INSERT|DROP|TRUNCATE|ALTER|RENAME)\\b").matcher(raw).find());
        assertEquals(2, Pattern.compile("(?i)CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+").matcher(raw).results().count());
        // H2 MODE=MySQL supports the real table definitions apart from MySQL's column charset annotations.
        String compatible = raw.replaceAll("(?i) CHARACTER SET ascii COLLATE ascii_bin", "");
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:miniapp-identity-migration;MODE=MySQL", "sa", "");
             var statement = connection.createStatement()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:"));
            statement.execute("CREATE TABLE t_user(id BIGINT PRIMARY KEY, mobile VARCHAR(20), role VARCHAR(16), status INT)");
            statement.execute("CREATE TABLE t_wechat_identity(id BIGINT PRIMARY KEY, app_id VARCHAR(64), openid VARCHAR(128), user_id BIGINT)");
            statement.execute("CREATE TABLE t_rider(id BIGINT PRIMARY KEY, user_id BIGINT, status VARCHAR(16), ride_enabled BOOLEAN, driving_enabled BOOLEAN, credit_score INT)");
            statement.execute("CREATE TABLE t_wallet_account(user_id BIGINT PRIMARY KEY, available_fen BIGINT, frozen_fen BIGINT)");
            statement.execute("CREATE TABLE t_order(id BIGINT PRIMARY KEY,user_id BIGINT,rider_id BIGINT,real_fee INT)");
            statement.execute("INSERT INTO t_user VALUES(7,'13800000001','RIDER',1)");
            statement.execute("INSERT INTO t_wechat_identity VALUES(17,'original-app','original-openid',7)");
            statement.execute("INSERT INTO t_rider VALUES(27,7,'FROZEN',TRUE,FALSE,81)");
            statement.execute("INSERT INTO t_wallet_account VALUES(7,12345,456)");
            statement.execute("INSERT INTO t_order VALUES(37,7,27,1200)");
            for (int repeat = 0; repeat < 3; repeat++) ScriptUtils.executeSqlScript(connection, new ByteArrayResource(compatible.getBytes(StandardCharsets.UTF_8)));
            try (var result = statement.executeQuery("SELECT u.id,u.mobile,u.role,u.status,i.id,i.app_id,i.openid,i.user_id,r.id,r.status,r.ride_enabled,r.driving_enabled,r.credit_score,w.available_fen,w.frozen_fen,o.id,o.user_id,o.rider_id,o.real_fee FROM t_user u JOIN t_wechat_identity i ON i.user_id=u.id JOIN t_rider r ON r.user_id=u.id JOIN t_wallet_account w ON w.user_id=u.id JOIN t_order o ON o.user_id=u.id")) {
                assertTrue(result.next()); assertEquals(7, result.getLong(1)); assertEquals("13800000001", result.getString(2));
                assertEquals("RIDER", result.getString(3)); assertEquals(1, result.getInt(4)); assertEquals(17, result.getLong(5));
                assertEquals("original-app", result.getString(6)); assertEquals("original-openid", result.getString(7)); assertEquals(7, result.getLong(8));
                assertEquals(27, result.getLong(9)); assertEquals("FROZEN", result.getString(10)); assertTrue(result.getBoolean(11));
                assertFalse(result.getBoolean(12)); assertEquals(81, result.getInt(13)); assertEquals(12345, result.getLong(14));
                assertEquals(456, result.getLong(15)); assertEquals(37, result.getLong(16)); assertEquals(7, result.getLong(17));
                assertEquals(27, result.getLong(18)); assertEquals(1200, result.getInt(19)); assertFalse(result.next());
            }
            for (String table : List.of("t_miniapp_login_ticket", "t_miniapp_link_code")) {
                try (var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) { assertTrue(result.next()); assertEquals(0, result.getInt(1)); }
            }
        }
    }

    @Test void freshSchemaAndExplicitMigrationListsContainBothProofTablesOnce() throws Exception {
        String schema = resource("db/schema.sql");
        for (String table : List.of("t_miniapp_login_ticket", "t_miniapp_link_code")) {
            assertEquals(1, Pattern.compile("(?i)CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+" + table + "\\s*\\(").matcher(schema).results().count());
        }
        for (String profile : List.of("application.yml", "application-local.yml", "application-docker.yml")) {
            assertEquals(1, resource(profile).split(Pattern.quote("classpath:" + MIGRATION), -1).length - 1, profile);
        }
        String raw = resource(MIGRATION);
        assertTrue(raw.contains("idx_miniapp_ticket_expiry")); assertTrue(raw.contains("idx_miniapp_link_expiry"));
        assertTrue(raw.contains("source_client <> target_client"));
        assertFalse(raw.contains("WECHAT_APP_SECRET")); assertFalse(raw.contains("LOGIN_RIDER"), "Login configuration remains owned by its independent migration");
    }
}
