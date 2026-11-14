package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.UserAddress;
import com.feilv.dto.AddressDtos.AddressReq;
import com.feilv.repository.UserAddressRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AddressService {
    private static final int MAX_ADDRESSES = 50;
    private final UserAddressRepository repository;

    @Transactional
    public UserAddress save(long userId, AddressReq req, Long id) {
        UserAddress address;
        if (id == null) {
            if (repository.countByUserId(userId) >= MAX_ADDRESSES) {
                throw BizException.of(400, "地址簿已达上限（50条）");
            }
            address = new UserAddress();
            address.setUserId(userId);
        } else {
            address = repository.findById(id).orElseThrow(() -> BizException.of(404, "地址不存在"));
            if (!Long.valueOf(userId).equals(address.getUserId())) throw BizException.of(403, "无权修改该地址");
        }
        AddressParts parts = normalizeAddress(req);
        address.setContact(req.contact());
        address.setPhone(req.phone());
        address.setPoiName(req.poiName() == null ? "" : req.poiName());
        address.setAddress(parts.fullAddress());
        // 旧客户端覆盖完整地址时清除旧结构化数据，避免两种地址表示不一致。
        address.setProvince(parts.province());
        address.setCity(parts.city());
        address.setDistrict(parts.district());
        address.setDetailAddress(parts.detailAddress());
        address.setLng(req.lng());
        address.setLat(req.lat());
        address.setIsDefault(req.isDefault());
        if (req.isDefault() == 1) clearDefault(userId, address.getId());
        return repository.save(address);
    }

    private AddressParts normalizeAddress(AddressReq req) {
        String province = trimToNull(req.province());
        String city = trimToNull(req.city());
        String district = trimToNull(req.district());
        String detail = trimToNull(req.detailAddress());
        if (province == null && city == null && district == null && detail == null) {
            if (req.address() == null || req.address().isBlank()) {
                throw BizException.of(400, "请填写完整地址");
            }
            checkLength(req.address(), 255, "完整地址");
            return new AddressParts(req.address(), null, null, null, null);
        }
        if (province == null || city == null || district == null || detail == null) {
            throw BizException.of(400, "请完整填写省、市、区及详细地址");
        }
        checkLength(province, 64, "省份");
        checkLength(city, 64, "城市");
        checkLength(district, 64, "区县");
        checkLength(detail, 160, "详细地址");
        String fullAddress = province + city + district + detail;
        checkLength(fullAddress, 255, "完整地址");
        return new AddressParts(fullAddress, province, city, district, detail);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private void checkLength(String value, int max, String field) {
        if (value.codePointCount(0, value.length()) > max) {
            throw BizException.of(400, field + "不能超过" + max + "个字符");
        }
    }

    private record AddressParts(String fullAddress, String province, String city,
                                String district, String detailAddress) {}

    @Transactional
    public void delete(long userId, long id) {
        UserAddress address = repository.findById(id).orElseThrow(() -> BizException.of(404, "地址不存在"));
        if (!Long.valueOf(userId).equals(address.getUserId())) throw BizException.of(403, "无权删除该地址");
        repository.delete(address);
    }

    private void clearDefault(long userId, Long exceptId) {
        repository.findByUserIdOrderByIsDefaultDescIdDesc(userId).stream()
                .filter(a -> !a.getId().equals(exceptId))
                .filter(a -> a.getIsDefault() == 1)
                .forEach(a -> { a.setIsDefault(0); repository.save(a); });
    }

    public List<UserAddress> list(long userId) {
        return repository.findByUserIdOrderByIsDefaultDescIdDesc(userId);
    }
}
