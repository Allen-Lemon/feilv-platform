package com.feilv.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.List;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** H2执行真实列/约束DDL；MySQL的information_schema/PREPARE条件分支单独验证，不访问远程库。 */
class TaskServicePricingMigrationTest {
    @Test void serviceColumnsAreAdditiveRepeatableAndKeepHistoricalPricingUntouched() throws Exception {
        String raw = new ClassPathResource("db/migrations/20261115_task_service_pricing.sql").getContentAsString(StandardCharsets.UTF_8);
        try (var c = DriverManager.getConnection("jdbc:h2:mem:task-service-migration;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""); var s = c.createStatement()) {
            s.execute("CREATE TABLE t_custom_task(id BIGINT AUTO_INCREMENT PRIMARY KEY,title VARCHAR(64),content VARCHAR(1000))");
            s.execute("CREATE TABLE t_order(id BIGINT PRIMARY KEY,estimate_fee INT,real_fee INT)");
            s.execute("INSERT INTO t_custom_task(title,content) VALUES('旧任务','完整旧需求')");
            s.execute("INSERT INTO t_order VALUES(1,1234,1234)");
            var ddl = Pattern.compile("'((?:ALTER TABLE)(?:''|[^'])+)'", Pattern.MULTILINE).matcher(raw);
            int count = 0;
            while (ddl.find()) {
                String sql = ddl.group(1).replace("''", "'");
                if (sql.contains("ADD COLUMN")) {
                    String name = sql.split("ADD COLUMN ")[1].split(" ")[0];
                    try (var r = s.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='T_CUSTOM_TASK' AND COLUMN_NAME='" + name.toUpperCase() + "'")) {
                        r.next(); assertEquals(0, r.getInt(1));
                    }
                    s.execute(sql); count++;
                } else { s.execute(sql); count++; }
            }
            assertEquals(7, count);
            // 第二次条件判断应跳过现有6列，正如MySQL迁移中的IF COUNT保护。
            for (String name : List.of("TASK_MODE", "PRICE_FEN", "CONTACT", "PHONE", "LOCATION_ADDRESS", "SERVICE_TIME")) {
                try (var r = s.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='T_CUSTOM_TASK' AND COLUMN_NAME='" + name + "'")) { r.next(); assertEquals(1, r.getInt(1)); }
            }
            try (var r = s.executeQuery("SELECT task_mode,price_fen,title FROM t_custom_task WHERE id=1")) {
                r.next(); assertEquals("DELIVERY", r.getString(1)); assertNull(r.getObject(2)); assertEquals("旧任务", r.getString(3));
            }
            try (var r = s.executeQuery("SELECT estimate_fee,real_fee FROM t_order WHERE id=1")) { r.next(); assertEquals(1234, r.getInt(1)); assertEquals(1234, r.getInt(2)); }
            assertThrows(java.sql.SQLException.class, () -> s.execute("INSERT INTO t_custom_task(title,price_fen) VALUES('非法',0)"));
        }
        assertEquals(6, raw.split("information_schema.columns", -1).length - 1);
        assertTrue(raw.contains("information_schema.table_constraints"));
        assertFalse(raw.matches("(?is).*UPDATE\\s+t_(order|pay_record|custom_task).*"));
    }
}
