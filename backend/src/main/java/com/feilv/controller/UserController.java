package com.feilv.controller;

import com.feilv.common.Result;
import com.feilv.common.UserContext;
import com.feilv.domain.User;
import com.feilv.dto.AddressDtos.AddressReq;
import com.feilv.repository.UserRepository;
import com.feilv.service.AddressService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/user")
@RequiredArgsConstructor
public class UserController {
    private final UserRepository userRepository;
    private final AddressService addressService;

    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        User u = userRepository.findById(UserContext.userId()).orElseThrow();
        return Result.ok(Map.of("userId", u.getId(), "mobile", (u.getMobile() == null ? "" : u.getMobile()).replaceAll("(\\d{3})\\d{4}(\\d{4})", "$1****$2"),
                "nickname", u.getNickname(), "role", u.getRole()));
    }

    @PostMapping("/address")
    public Result<Void> addAddress(@Valid @RequestBody AddressReq req) {
        addressService.save(UserContext.userId(), req, null);
        return Result.ok();
    }

    @PutMapping("/address/{id}")
    public Result<Void> updateAddress(@PathVariable long id, @Valid @RequestBody AddressReq req) {
        addressService.save(UserContext.userId(), req, id);
        return Result.ok();
    }

    @DeleteMapping("/address/{id}")
    public Result<Void> deleteAddress(@PathVariable long id) {
        addressService.delete(UserContext.userId(), id);
        return Result.ok();
    }

    @GetMapping("/address")
    public Result<List<com.feilv.domain.UserAddress>> listAddress() {
        return Result.ok(addressService.list(UserContext.userId()));
    }
}
