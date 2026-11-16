package com.feilv.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.List;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** 离线执行真实DDL，核对MySQL条件迁移保护；不连接部署数据库。 */
class BuyBudgetTagModesMigrationTest {
    private String resource(String name) throws Exception {
        return new ClassPathResource(name).getContentAsString(StandardCharsets.UTF_8);
    }

    @Test void budgetAndModesAreAdditiveRepeatableAndDoNotRewriteHistory() throws Exception {
        String raw = resource("db/migrations/20261116_buy_budget_task_tag_modes.sql");
        try (var c = DriverManager.getConnection("jdbc:h2:mem:budget-tag-migration;MODE=MySQL", "sa", ""); var s = c.createStatement()) {
            s.execute("CREATE TABLE t_order(id BIGINT PRIMARY KEY,estimate_fee INT,real_fee INT)");
            s.execute("CREATE TABLE t_task_tag(id BIGINT PRIMARY KEY,name VARCHAR(32))");
            s.execute("INSERT INTO t_order VALUES(1,1234,1234)");
            s.execute("INSERT INTO t_task_tag VALUES(1,'历史标签')");
            for (int round = 0; round < 2; round++) {
                var ddl = Pattern.compile("'((?:ALTER TABLE)(?:''|[^'])+)'", Pattern.MULTILINE).matcher(raw);
                int executed = 0;
                while (ddl.find()) {
                    String sql = ddl.group(1).replace("''", "'");
                    String[] words = sql.split(" ");
                    String table = words[2].toUpperCase();
                    boolean column = sql.contains("ADD COLUMN");
                    String name = words[5].toUpperCase();
                    String exists = column
                        ? "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='" + table + "' AND COLUMN_NAME='" + name + "'"
                        : "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_NAME='" + table + "' AND CONSTRAINT_NAME='" + name + "'";
                    try (var r = s.executeQuery(exists)) { r.next(); if (r.getInt(1) > 0) continue; }
                    s.execute(sql); executed++;
                }
                assertEquals(round == 0 ? 3 : 0, executed);
            }
            try (var r = s.executeQuery("SELECT estimate_fee,real_fee,goods_budget_fen FROM t_order WHERE id=1")) {
                assertTrue(r.next()); assertEquals(1234, r.getInt(1)); assertEquals(1234, r.getInt(2)); assertNull(r.getObject(3));
            }
            try (var r = s.executeQuery("SELECT name,task_modes FROM t_task_tag WHERE id=1")) {
                assertTrue(r.next()); assertEquals("历史标签", r.getString(1)); assertNull(r.getObject(2));
            }
            s.execute("UPDATE t_order SET goods_budget_fen=29 WHERE id=1");
            for (int value : List.of(0, -1, 100000001)) {
                assertThrows(java.sql.SQLException.class, () -> s.execute("UPDATE t_order SET goods_budget_fen=" + value + " WHERE id=1"));
            }
            s.execute("UPDATE t_order SET goods_budget_fen=100000000 WHERE id=1");
            s.execute("UPDATE t_order SET goods_budget_fen=NULL WHERE id=1");
        }
        assertEquals(2, raw.split("information_schema.columns", -1).length - 1);
        assertEquals(1, raw.split("information_schema.table_constraints", -1).length - 1);
        assertFalse(raw.matches("(?is).*\\b(?:UPDATE|DELETE|INSERT)\\s+.*"));
        for (String profile : List.of("application.yml", "application-local.yml", "application-docker.yml")) {
            assertTrue(resource(profile).contains("classpath:db/migrations/20261116_buy_budget_task_tag_modes.sql"));
            assertTrue(resource(profile).contains("classpath:db/migrations/20261116_retire_admin_menus.sql"));
        }
        String schema = resource("db/schema.sql");
        assertTrue(schema.contains("goods_budget_fen INT DEFAULT NULL"));
        assertTrue(schema.contains("task_modes VARCHAR(64) DEFAULT NULL"));
        assertTrue(schema.contains("chk_order_goods_budget"));
    }

    @Test void retirementIsRepeatableKeepsRoleHistoryAndLeavesActiveMenusUntouched() throws Exception {
        String sql = resource("db/migrations/20261116_retire_admin_menus.sql");
        try (var c = DriverManager.getConnection("jdbc:h2:mem:retired-menu-migration;MODE=MySQL", "sa", ""); var s = c.createStatement()) {
            s.execute("CREATE TABLE t_admin_menu(id BIGINT PRIMARY KEY,code VARCHAR(32),path VARCHAR(128),enabled BOOLEAN)");
            s.execute("CREATE TABLE t_admin_role_menu(role_id BIGINT,menu_id BIGINT)");
            s.execute("INSERT INTO t_admin_menu VALUES(1,'marketing','/marketing',true),(2,'content','/content',true),(3,'risk','/risk',true),(4,'complaints','/complaints',true),(5,'orders','/orders',true),(6,'custom-code','/risk',true)");
            s.execute("INSERT INTO t_admin_role_menu VALUES(1,1),(1,4)");
            s.execute(sql); s.execute(sql);
            try (var r = s.executeQuery("SELECT COUNT(*) FROM t_admin_menu WHERE enabled=true")) { r.next(); assertEquals(2, r.getInt(1)); }
            try (var r = s.executeQuery("SELECT COUNT(*) FROM t_admin_menu")) { r.next(); assertEquals(6, r.getInt(1)); }
            try (var r = s.executeQuery("SELECT COUNT(*) FROM t_admin_role_menu")) { r.next(); assertEquals(2, r.getInt(1)); }
        }
        String schema = resource("db/schema.sql");
        for (String code : List.of("marketing", "content", "risk")) assertFalse(schema.contains("SELECT '" + code + "'"));
        assertTrue(schema.contains("SELECT 'orders'"));
    }
}
