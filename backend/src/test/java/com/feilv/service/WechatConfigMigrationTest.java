package com.feilv.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

/** 迁移只对H2的MySQL兼容模式执行，验证新增配置表/菜单脚本可重复且不重置配置。 */
class WechatConfigMigrationTest {
    @Test void newSchemaAndMenuMigrationCanRunTwiceWithoutReset() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:h2:mem:wechat-migration;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""); var s = c.createStatement()) {
            s.execute("CREATE TABLE t_admin_menu (id BIGINT AUTO_INCREMENT PRIMARY KEY,code VARCHAR(32) UNIQUE,name VARCHAR(64),path VARCHAR(128),parent_id BIGINT,sort INT,enabled INT)");
            s.execute("CREATE TABLE t_admin_role (id BIGINT AUTO_INCREMENT PRIMARY KEY,code VARCHAR(32),enabled INT)");
            s.execute("CREATE TABLE t_admin_role_menu (id BIGINT AUTO_INCREMENT PRIMARY KEY,role_id BIGINT,menu_id BIGINT,UNIQUE(role_id,menu_id))");
            s.execute("INSERT INTO t_admin_role(code,enabled) VALUES ('SUPER_ADMIN',1)");
            var script = new ClassPathResource("db/migrations/20261113_wechat_config.sql");
            ScriptUtils.executeSqlScript(c, script);
            s.execute("INSERT INTO t_wechat_config(section,version,enabled,app_id) VALUES ('LOGIN',4,FALSE,'wx1234567890abcdef')");
            ScriptUtils.executeSqlScript(c, script);
            try (var r = s.executeQuery("SELECT COUNT(*) FROM t_admin_menu WHERE code='wechat-settings'")) { r.next(); assertEquals(1, r.getInt(1)); }
            try (var r = s.executeQuery("SELECT COUNT(*) FROM t_admin_role_menu")) { r.next(); assertEquals(1, r.getInt(1)); }
            try (var r = s.executeQuery("SELECT version FROM t_wechat_config WHERE section='LOGIN'")) { r.next(); assertEquals(4, r.getLong(1)); }
        }
    }
}
