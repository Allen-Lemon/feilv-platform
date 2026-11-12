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
        address.setContact(req.contact());
        address.setPhone(req.phone());
        address.setPoiName(req.poiName() == null ? "" : req.poiName());
        address.setAddress(req.address());
        address.setLng(req.lng());
        address.setLat(req.lat());
        address.setIsDefault(req.isDefault());
        if (req.isDefault() == 1) clearDefault(userId, address.getId());
        return repository.save(address);
    }

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
