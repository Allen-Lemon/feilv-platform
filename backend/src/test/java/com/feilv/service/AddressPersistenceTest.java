package com.feilv.service;

import com.feilv.domain.UserAddress;
import com.feilv.dto.AddressDtos.AddressReq;
import com.feilv.repository.UserAddressRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** 仅本地嵌入式 H2，禁用启动 SQL（MySQL 的 information_schema/PREPARE 迁移不在 H2 执行）。 */
@DataJpaTest(showSql = false, properties = {"spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"})
@Import(AddressService.class)
class AddressPersistenceTest {
    @Autowired AddressService service;
    @Autowired UserAddressRepository repository;
    @Autowired TestEntityManager entities;
    @Autowired JdbcTemplate jdbc;

    @Test
    void structuredFieldsAndAuthoritativeFullAddressSurviveDatabaseRoundTrip() {
        var request = new AddressReq("张三", "13800138000", "中心广场", "客户端旧地址",
                new BigDecimal("108.120000"), new BigDecimal("28.250000"), 1,
                " 贵州省 ", " 铜仁市 ", " 德江县 ", " 青龙街道中心街8号301室 ");
        long id = service.save(42, request, null).getId();
        entities.flush(); entities.clear();
        UserAddress saved = repository.findById(id).orElseThrow();
        assertEquals("贵州省", saved.getProvince()); assertEquals("铜仁市", saved.getCity());
        assertEquals("德江县", saved.getDistrict()); assertEquals("青龙街道中心街8号301室", saved.getDetailAddress());
        assertEquals("贵州省铜仁市德江县青龙街道中心街8号301室", saved.getAddress());
        assertEquals("中心广场", saved.getPoiName());
        assertEquals(0, request.lng().compareTo(saved.getLng()));
        assertEquals(0, request.lat().compareTo(saved.getLat()));
        assertEquals(1, saved.getIsDefault()); assertEquals(42L, saved.getUserId());
        assertEquals(1, service.list(42).size()); assertTrue(service.list(99).isEmpty());
    }

    @Test
    void legacyRowsRemainNullableAndLegacyUpdatesClearPersistedStructuredParts() {
        long legacy = service.save(42, new AddressReq("甲", "13800138000", null,
                "未拆分的旧地址", null, null, 0), null).getId();
        long current = service.save(42, new AddressReq("乙", "13800138001", null,
                null, null, null, 0, "贵州省", "铜仁市", "德江县", "1号"), null).getId();
        entities.flush(); entities.clear();
        assertEquals("未拆分的旧地址", repository.findById(legacy).orElseThrow().getAddress());
        assertNull(repository.findById(legacy).orElseThrow().getProvince());
        service.save(42, new AddressReq("乙", "13800138001", "旧POI", "旧客户端更改地址", null, null, 0), current);
        entities.flush(); entities.clear();
        UserAddress updated = repository.findById(current).orElseThrow();
        assertEquals("旧客户端更改地址", updated.getAddress());
        assertNull(updated.getProvince()); assertNull(updated.getCity());
        assertNull(updated.getDistrict()); assertNull(updated.getDetailAddress());
    }

    @Test
    void creatingAndUpdatingDefaultKeepsOtherUsersDefaultAndListOrdering() {
        var legacy = new AddressReq("甲", "13800138000", null, "旧地址", null, null, 1);
        long previous = service.save(42, legacy, null).getId();
        long otherUser = service.save(99, legacy, null).getId();
        long next = service.save(42, new AddressReq("乙", "13800138001", null, null, null, null, 1,
                "贵州省", "铜仁市", "德江县", "2号"), null).getId();
        entities.flush(); entities.clear();
        assertEquals(0, repository.findById(previous).orElseThrow().getIsDefault());
        assertEquals(1, repository.findById(next).orElseThrow().getIsDefault());
        assertEquals(1, repository.findById(otherUser).orElseThrow().getIsDefault());
        service.save(42, legacy, previous);
        entities.flush(); entities.clear();
        assertEquals(0, repository.findById(next).orElseThrow().getIsDefault());
        assertEquals(1, repository.findById(previous).orElseThrow().getIsDefault());
        assertEquals(previous, service.list(42).get(0).getId());
        assertEquals(1, repository.findById(otherUser).orElseThrow().getIsDefault());
    }

    @Test
    void entityColumnLengthsMatchMysqlSchema() {
        for (String column : new String[]{"PROVINCE", "CITY", "DISTRICT"}) assertColumnLength(column, 64);
        assertColumnLength("DETAIL_ADDRESS", 160);
        assertColumnLength("ADDRESS", 255);
    }

    private void assertColumnLength(String column, int expected) {
        assertEquals(expected, jdbc.queryForObject("""
                SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = 'T_USER_ADDRESS' AND COLUMN_NAME = ?
                """, Integer.class, column));
    }
}
