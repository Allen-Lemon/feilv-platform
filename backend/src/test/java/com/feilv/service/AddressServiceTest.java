package com.feilv.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feilv.common.BizException;
import com.feilv.domain.UserAddress;
import com.feilv.dto.AddressDtos.AddressReq;
import com.feilv.repository.UserAddressRepository;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AddressServiceTest {
    private final UserAddressRepository repository = mock(UserAddressRepository.class);
    private final AddressService service = new AddressService(repository);

    @BeforeEach
    void setup() {
        when(repository.save(any(UserAddress.class))).thenAnswer(call -> call.getArgument(0));
    }

    private AddressReq structured(String province, String city, String district, String detail) {
        return new AddressReq("联系人", "13800138000", "测试POI", "冲突的客户端地址",
                new BigDecimal("108.12"), new BigDecimal("28.25"), 0, province, city, district, detail);
    }

    private UserAddress existing(long id, long owner, int isDefault) {
        UserAddress address = new UserAddress();
        address.setId(id); address.setUserId(owner); address.setIsDefault(isDefault);
        when(repository.findById(id)).thenReturn(Optional.of(address));
        return address;
    }

    @Test
    void legacySevenArgumentConstructorPreservesFullAddressAndOriginalFields() {
        AddressReq request = new AddressReq("联系人", "13800138000", "POI", "  历史完整地址  ",
                BigDecimal.ONE, BigDecimal.TEN, 0);
        UserAddress saved = service.save(42, request, null);
        assertEquals("  历史完整地址  ", saved.getAddress());
        assertEquals(42L, saved.getUserId());
        assertEquals("联系人", saved.getContact());
        assertEquals("13800138000", saved.getPhone());
        assertEquals("POI", saved.getPoiName());
        assertEquals(BigDecimal.ONE, saved.getLng());
        assertEquals(BigDecimal.TEN, saved.getLat());
        assertNull(saved.getProvince()); assertNull(saved.getCity());
        assertNull(saved.getDistrict()); assertNull(saved.getDetailAddress());
    }

    @Test
    void allBlankStructuredFieldsAreLegacyAndNullPoiStillBecomesEmpty() {
        var request = new AddressReq("联系人", "13800138000", null, "旧完整地址", null, null, 0,
                "  ", "\t", null, "\n");
        UserAddress saved = service.save(42, request, null);
        assertEquals("旧完整地址", saved.getAddress());
        assertEquals("", saved.getPoiName());
        assertNull(saved.getProvince()); assertNull(saved.getCity());
        assertNull(saved.getDistrict()); assertNull(saved.getDetailAddress());
    }

    @Test
    void trimsStructuredFieldsAndComposesAddressWithoutRegionDatabaseLookup() {
        UserAddress saved = service.save(42, structured("  自定义省  ", " 自定义市 ", " 自定义区 ", "  街道8号  "), null);
        assertEquals("自定义省", saved.getProvince());
        assertEquals("自定义市", saved.getCity());
        assertEquals("自定义区", saved.getDistrict());
        assertEquals("街道8号", saved.getDetailAddress());
        assertEquals("自定义省自定义市自定义区街道8号", saved.getAddress());
        assertEquals("测试POI", saved.getPoiName());
        assertEquals(new BigDecimal("108.12"), saved.getLng());
        assertEquals(new BigDecimal("28.25"), saved.getLat());
        verify(repository).countByUserId(42L);
        verify(repository).save(saved);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void jsonAcceptsOldRequestsAndStructuredRequestsWithoutFullAddress() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AddressReq old = mapper.readValue("""
                {"contact":"甲","phone":"13800138000","address":"历史地址","isDefault":0}
                """, AddressReq.class);
        assertNull(old.province());
        assertEquals("历史地址", service.save(42, old, null).getAddress());
        AddressReq current = mapper.readValue("""
                {"contact":"甲","phone":"13800138000","isDefault":0,
                 "province":"贵州省","city":"铜仁市","district":"德江县","detailAddress":"中心街1号"}
                """, AddressReq.class);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(current).isEmpty());
        }
        assertNull(current.address());
        assertEquals("贵州省铜仁市德江县中心街1号", service.save(42, current, null).getAddress());
    }

    @Test
    void ignoresEvenOversizedConflictingClientAddressForCompleteStructuredRequest() {
        var request = new AddressReq("甲", "13800138000", null, "冲突".repeat(256), null, null, 0,
                "贵州省", "铜仁市", "德江县", "中心街1号");
        assertEquals("贵州省铜仁市德江县中心街1号", service.save(42, request, null).getAddress());
    }

    @Test
    void rejectsPartialStructuredRequestsIncludingWhitespaceOnlyParts() {
        for (AddressReq req : List.of(
                structured("省", null, null, null), structured(null, "市", null, null),
                structured(null, null, "区", null), structured(null, null, null, "街1号"),
                structured("省", "市", "区", "  "), structured("省", "市", "\t", "街1号"),
                structured("省", " ", "区", "街1号"), structured(" ", "市", "区", "街1号"))) {
            assertEquals(400, assertThrows(BizException.class, () -> service.save(42, req, null)).getCode());
        }
        verify(repository, never()).save(any());
        verify(repository, never()).findByUserIdOrderByIsDefaultDescIdDesc(any());
    }

    @Test
    void validatesEachStructuredFieldAndCombinedLengthAfterTrimming() {
        for (AddressReq req : List.of(
                structured("省".repeat(65), "市", "区", "1号"),
                structured("省", "市".repeat(65), "区", "1号"),
                structured("省", "市", "区".repeat(65), "1号"),
                structured("省", "市", "区", "号".repeat(161)),
                structured("省".repeat(64), "市".repeat(64), "区".repeat(64), "号".repeat(64)))) {
            assertEquals(400, assertThrows(BizException.class, () -> service.save(42, req, null)).getCode());
        }
        verify(repository, never()).save(any());
        // 每项都在上限以内，但组合长度仍独立校验；首尾空格不计入结构化字段。
        assertEquals(255, service.save(42, structured(" " + "省".repeat(64) + " ",
                "市".repeat(64), "区".repeat(64), "号".repeat(63)), null).getAddress().length());
        assertEquals(160, service.save(42, structured("省", "市", "区", "号".repeat(160)), null)
                .getDetailAddress().length());
        // MySQL VARCHAR 字符数按 Unicode 码点，而非 UTF-16 单元计数。
        assertEquals("😀".repeat(64), service.save(42, structured("😀".repeat(64), "市", "区", "1号"), null)
                .getProvince());
    }

    @Test
    void legacyRequiresNonblankFullAddressWithin255Characters() {
        for (String full : new String[]{null, "", "   ", "\t\n", "址".repeat(256)}) {
            AddressReq req = new AddressReq("甲", "13800138000", null, full, null, null, 0);
            assertEquals(400, assertThrows(BizException.class, () -> service.save(42, req, null)).getCode());
        }
        assertEquals("址".repeat(255), service.save(42,
                new AddressReq("甲", "13800138000", null, "址".repeat(255), null, null, 0), null).getAddress());
    }

    @Test
    void legacyUpdateClearsAllOldStructuredFieldsEvenWhenFullAddressMatches() {
        UserAddress address = existing(8, 42, 0);
        address.setProvince("省"); address.setCity("市"); address.setDistrict("区"); address.setDetailAddress("1号");
        address.setAddress("省市区1号");
        var req = new AddressReq("甲", "13800138000", null, "省市区1号", null, null, 0);
        assertSame(address, service.save(42, req, 8L));
        assertNull(address.getProvince()); assertNull(address.getCity());
        assertNull(address.getDistrict()); assertNull(address.getDetailAddress());
        assertEquals("省市区1号", address.getAddress());
    }

    @Test
    void missingAndForeignAddressesCannotBeUpdatedOrDeleted() {
        var req = structured("省", "市", "区", "1号");
        assertEquals(404, assertThrows(BizException.class, () -> service.save(42, req, 99L)).getCode());
        assertEquals(404, assertThrows(BizException.class, () -> service.delete(42, 99)).getCode());
        UserAddress foreign = existing(8, 99, 1);
        foreign.setAddress("他人地址");
        assertEquals(403, assertThrows(BizException.class, () -> service.save(42, req, 8L)).getCode());
        assertEquals(403, assertThrows(BizException.class, () -> service.delete(42, 8)).getCode());
        assertEquals("他人地址", foreign.getAddress());
        assertEquals(1, foreign.getIsDefault());
        verify(repository, never()).save(any()); verify(repository, never()).delete(any(UserAddress.class));
    }

    @Test
    void enforcesCapacityOnlyForNewAddressesAndPreservesAuthorizedDelete() {
        when(repository.countByUserId(42L)).thenReturn(50L);
        var req = structured("省", "市", "区", "1号");
        assertEquals(400, assertThrows(BizException.class, () -> service.save(42, req, null)).getCode());
        UserAddress address = existing(8, 42, 0);
        assertSame(address, service.save(42, req, 8L));
        service.delete(42, 8);
        verify(repository).delete(address);
    }

    @Test
    void changingDefaultOnlyClearsOtherAddressesOfTheCurrentUser() {
        UserAddress previous = existing(1, 42, 1), target = existing(2, 42, 0), ordinary = existing(3, 42, 0);
        when(repository.findByUserIdOrderByIsDefaultDescIdDesc(42L)).thenReturn(List.of(previous, target, ordinary));
        var req = new AddressReq("甲", "13800138000", null, null, null, null, 1, "省", "市", "区", "1号");
        service.save(42, req, 2L);
        assertEquals(0, previous.getIsDefault()); assertEquals(1, target.getIsDefault());
        verify(repository).save(previous); verify(repository).save(target);
        verify(repository, never()).save(ordinary);
        verify(repository, never()).findByUserIdOrderByIsDefaultDescIdDesc(99L);
        assertEquals(List.of(previous, target, ordinary), service.list(42));
    }

    @Test
    void invalidUpdateDoesNotMutateExistingAddressOrClearDefault() {
        UserAddress target = existing(2, 42, 1);
        target.setAddress("原地址"); target.setProvince("原省");
        var req = new AddressReq("甲", "13800138000", null, "新地址", null, null, 1, "省", null, "区", "1号");
        assertEquals(400, assertThrows(BizException.class, () -> service.save(42, req, 2L)).getCode());
        assertEquals("原地址", target.getAddress()); assertEquals("原省", target.getProvince());
        assertEquals(1, target.getIsDefault());
        verify(repository, never()).save(any());
        verify(repository, never()).findByUserIdOrderByIsDefaultDescIdDesc(any());
    }
}
