package com.feilv.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class WalletMigrationTest {
    @Test void migrationIsAdditiveRepeatableAndDoesNotBackfillHistoricalBalances() throws Exception {
        String raw = new ClassPathResource("db/migrations/20261115_wallet.sql").getContentAsString(StandardCharsets.UTF_8);
        // H2没有MySQL列级字符集语法；仅移除该语法，其余表/唯一约束/CHECK与菜单SQL真实执行两次。
        String script = raw.replace(" CHARACTER SET ascii COLLATE ascii_bin", "");
        try (var c = DriverManager.getConnection("jdbc:h2:mem:wallet-migration;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""); var s = c.createStatement()) {
            s.execute("CREATE TABLE t_admin_menu(id BIGINT AUTO_INCREMENT PRIMARY KEY,code VARCHAR(32) UNIQUE,name VARCHAR(64),path VARCHAR(128),parent_id BIGINT,sort INT,enabled INT)");
            s.execute("CREATE TABLE t_admin_role(id BIGINT AUTO_INCREMENT PRIMARY KEY,code VARCHAR(32),enabled INT)");
            s.execute("CREATE TABLE t_admin_role_menu(id BIGINT AUTO_INCREMENT PRIMARY KEY,role_id BIGINT,menu_id BIGINT,UNIQUE(role_id,menu_id))");
            s.execute("INSERT INTO t_admin_role(code,enabled) VALUES('SUPER_ADMIN',1)");
            var resource = new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8));
            ScriptUtils.executeSqlScript(c, resource);
            s.execute("INSERT INTO t_wallet_account(user_id,available_fen,created_time,updated_time) VALUES(42,500,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            s.execute("INSERT INTO t_wallet_withdrawal(user_id,amount_fen,request_id,created_time) VALUES(42,100,'migration-request-1',CURRENT_TIMESTAMP)");
            ScriptUtils.executeSqlScript(c, resource);
            try (var r = s.executeQuery("SELECT available_fen FROM t_wallet_account WHERE user_id=42")) { r.next(); assertEquals(500, r.getLong(1)); }
            try (var r = s.executeQuery("SELECT COUNT(*) FROM t_wallet_account")) { r.next(); assertEquals(1, r.getLong(1)); }
            try (var r = s.executeQuery("SELECT COUNT(*) FROM t_admin_menu WHERE code IN('wallets','withdrawals')")) { r.next(); assertEquals(2, r.getLong(1)); }
            try (var r = s.executeQuery("SELECT COUNT(*) FROM t_admin_role_menu")) { r.next(); assertEquals(2, r.getLong(1)); }
            assertThrows(java.sql.SQLException.class, () -> s.execute("INSERT INTO t_wallet_withdrawal(user_id,amount_fen,request_id,created_time) VALUES(42,100,'migration-request-1',CURRENT_TIMESTAMP)"));
            assertThrows(java.sql.SQLException.class, () -> s.execute("UPDATE t_wallet_account SET debt_fen=1 WHERE user_id=42"));
        }
        assertTrue(raw.contains("COLLATE ascii_bin"));
        assertFalse(raw.matches("(?is).*UPDATE\\s+t_pay_record.*"));
        assertFalse(raw.matches("(?is).*INSERT\\s+INTO\\s+t_wallet_account.*"));
    }
}
