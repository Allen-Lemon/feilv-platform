package com.feilv.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class CustomTaskMigrationTest {
    @Test void additiveTablesAndMenuMigrationAreRepeatableAndPreserveTaskAndDeductionData() throws Exception {
        String sql = new ClassPathResource("db/migrations/20261115_custom_tasks.sql").getContentAsString(StandardCharsets.UTF_8);
        // MySQL information_schema/PREPARE 索引分支不能在H2执行；其余DDL和菜单语句真实执行两次。
        String h2Sql = sql.replaceAll("(?s)SET @complaint_index_sql.*?DEALLOCATE PREPARE complaint_index_stmt;", "");
        try (var c = DriverManager.getConnection("jdbc:h2:mem:task-migration;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""); var s = c.createStatement()) {
            s.execute("CREATE TABLE t_admin_menu(id BIGINT AUTO_INCREMENT PRIMARY KEY,code VARCHAR(32) UNIQUE,name VARCHAR(64),path VARCHAR(128),parent_id BIGINT,sort INT,enabled INT)");
            s.execute("CREATE TABLE t_admin_role(id BIGINT AUTO_INCREMENT PRIMARY KEY,code VARCHAR(32),enabled INT)");
            s.execute("CREATE TABLE t_admin_role_menu(id BIGINT AUTO_INCREMENT PRIMARY KEY,role_id BIGINT,menu_id BIGINT,UNIQUE(role_id,menu_id))");
            s.execute("INSERT INTO t_admin_role(code,enabled) VALUES('SUPER_ADMIN',1)");
            var script = new ByteArrayResource(h2Sql.getBytes(StandardCharsets.UTF_8));
            ScriptUtils.executeSqlScript(c, script);
            s.execute("INSERT INTO t_task_tag(name) VALUES('保留标签')");
            s.execute("INSERT INTO t_complaint_deduction(complaint_id,order_id,rider_id,user_id,amount_fen,created_by) VALUES(1,2,3,4,100,5)");
            ScriptUtils.executeSqlScript(c, script);
            try (var result = s.executeQuery("SELECT COUNT(*) FROM t_admin_menu WHERE code IN ('tasks','complaints')")) { result.next(); assertEquals(2, result.getInt(1)); }
            try (var result = s.executeQuery("SELECT COUNT(*) FROM t_admin_role_menu")) { result.next(); assertEquals(2, result.getInt(1)); }
            try (var result = s.executeQuery("SELECT amount_fen FROM t_complaint_deduction WHERE complaint_id=1")) { result.next(); assertEquals(100, result.getInt(1)); }
            try (var result = s.executeQuery("SELECT name FROM t_task_tag")) { result.next(); assertEquals("保留标签", result.getString(1)); }
        }
        assertTrue(sql.contains("information_schema.statistics"));
        assertFalse(sql.matches("(?is).*UPDATE\\s+t_pay_record.*"));
    }
}
