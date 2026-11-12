package com.feilv.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public final class AddressDtos {
    private AddressDtos() {}

    public record AddressReq(@NotBlank String contact, @NotBlank String phone,
                             String poiName, @NotBlank String address,
                             BigDecimal lng, BigDecimal lat, @NotNull Integer isDefault) {}
}
