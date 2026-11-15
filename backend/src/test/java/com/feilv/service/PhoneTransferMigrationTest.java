package com.feilv.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class PhoneTransferMigrationTest {
    @Test void transferTableIsRepeatableAndDoesNotRewriteOrderOrUserData() throws Exception {
        var script = new ClassPathResource("db/migrations/20261115_phone_transfer.sql");
        try (var c = DriverManager.getConnection("jdbc:h2:mem:phone-transfer-migration;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""); var s = c.createStatement()) {
            s.execute("CREATE TABLE t_user(id BIGINT PRIMARY KEY,mobile VARCHAR(20))");
            s.execute("CREATE TABLE t_order(id BIGINT PRIMARY KEY,rider_id BIGINT,real_fee INT)");
            s.execute("INSERT INTO t_user VALUES(1,'13800138000')"); s.execute("INSERT INTO t_order VALUES(1,11,1000)");
            ScriptUtils.executeSqlScript(c, script);
            s.execute("INSERT INTO t_order_transfer(order_id,from_rider_id,to_rider_id,reason) VALUES(1,11,12,'时间冲突')");
            ScriptUtils.executeSqlScript(c, script);
            try (var r = s.executeQuery("SELECT COUNT(*) FROM t_order_transfer")) { r.next(); assertEquals(1, r.getInt(1)); }
            try (var r = s.executeQuery("SELECT rider_id,real_fee FROM t_order WHERE id=1")) { r.next(); assertEquals(11, r.getLong(1)); assertEquals(1000, r.getInt(2)); }
            try (var r = s.executeQuery("SELECT mobile FROM t_user WHERE id=1")) { r.next(); assertEquals("13800138000", r.getString(1)); }
            assertThrows(java.sql.SQLException.class, () -> s.execute("INSERT INTO t_order_transfer(order_id,from_rider_id,to_rider_id,reason) VALUES(1,11,11,'自转')"));
            assertThrows(java.sql.SQLException.class, () -> s.execute("INSERT INTO t_order_transfer(order_id,from_rider_id,to_rider_id,status,reason) VALUES(1,11,12,'UNKNOWN','非法状态')"));
        }
        String sql = script.getContentAsString(StandardCharsets.UTF_8);
        assertFalse(sql.matches("(?is).*UPDATE\\s+t_(user|order|pay_record|wallet_account).*"));
    }
}
