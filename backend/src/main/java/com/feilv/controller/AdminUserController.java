package com.feilv.controller;

import com.feilv.common.Result;
import com.feilv.dto.AdminUserDtos.StatusReq;
import com.feilv.dto.AdminUserDtos.UserVO;
import com.feilv.service.AdminMenuAccess;
import com.feilv.service.AdminQueryParameters;
import com.feilv.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {
    private final AdminUserService users;
    private final AdminMenuAccess access;

    @GetMapping
    public Result<Page<UserVO>> list(@RequestParam(defaultValue = "0") String page,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String mobileBound) {
        access.requireMenu("users");
        return Result.ok(users.list(AdminQueryParameters.page(page), keyword,
                AdminQueryParameters.userStatus(status), AdminQueryParameters.mobileBound(mobileBound)));
    }

    @GetMapping("/{id}")
    public Result<UserVO> detail(@PathVariable String id) {
        access.requireMenu("users");
        return Result.ok(users.detail(AdminQueryParameters.id(id)));
    }

    @PostMapping("/{id}/status")
    public Result<UserVO> status(@PathVariable String id, @Valid @RequestBody StatusReq request) {
        access.requireMenu("users");
        return Result.ok(users.changeStatus(AdminQueryParameters.id(id), request.status()));
    }
}
