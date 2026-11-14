package com.feilv.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public final class AddressDtos {
    private AddressDtos() {}

    // address 在旧请求中必填；结构化请求由服务端组合，条件校验在 AddressService 执行。
    public record AddressReq(@NotBlank String contact, @NotBlank String phone,
                             String poiName, String address,
                             BigDecimal lng, BigDecimal lat, @NotNull Integer isDefault,
                             String province, String city, String district, String detailAddress) {
        public AddressReq(String contact, String phone, String poiName, String address,
                          BigDecimal lng, BigDecimal lat, Integer isDefault) {
            this(contact, phone, poiName, address, lng, lat, isDefault, null, null, null, null);
        }
    }
}
