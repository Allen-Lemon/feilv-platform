package com.feilv.controller;

import com.feilv.common.Result;
import com.feilv.domain.AdminAccount;
import com.feilv.domain.AdminMenu;
import com.feilv.domain.AdminRole;
import com.feilv.domain.AdminRoleMenu;
import com.feilv.domain.AdminUserRole;
import com.feilv.domain.User;
import com.feilv.repository.*;
import com.feilv.service.AuditService;
import com.feilv.util.PasswordUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/system")
@RequiredArgsConstructor
public class SystemAdminController {
    private final AdminMenuRepository menuRepository;
    private final AdminRoleRepository roleRepository;
    private final AdminAccountRepository accountRepository;
    private final AdminUserRoleRepository userRoleRepository;
    private final AdminRoleMenuRepository roleMenuRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    @GetMapping("/menus")
    public Result<List<AdminMenu>> menus() {
        return Result.ok(menuRepository.findByEnabledOrderBySortAscIdAsc(1));
    }

    @PostMapping("/menus")
    public Result<AdminMenu> saveMenu(@RequestBody Map<String, Object> body) {
        AdminMenu menu = body.get("id") == null ? new AdminMenu() : menuRepository.findById(Long.parseLong(body.get("id").toString())).orElseThrow();
        menu.setCode(String.valueOf(body.getOrDefault("code", "")));
        menu.setName(String.valueOf(body.getOrDefault("name", "")));
        menu.setPath(String.valueOf(body.getOrDefault("path", "")));
        menu.setParentId(Long.parseLong(String.valueOf(body.getOrDefault("parentId", 0))));
        menu.setSort(Integer.parseInt(String.valueOf(body.getOrDefault("sort", 0))));
        menu.setEnabled(Integer.parseInt(String.valueOf(body.getOrDefault("enabled", 1))));
        AdminMenu saved = menuRepository.save(menu);
        auditService.record("SYSTEM_MENU_SAVE", "menuId=" + saved.getId());
        return Result.ok(saved);
    }

    @DeleteMapping("/menus/{id}")
    public Result<Void> deleteMenu(@PathVariable long id) {
        AdminMenu menu = menuRepository.findById(id).orElseThrow();
        menu.setEnabled(0);
        menuRepository.save(menu);
        auditService.record("SYSTEM_MENU_DISABLE", "menuId=" + id);
        return Result.ok();
    }

    @GetMapping("/roles")
    public Result<List<AdminRole>> roles() { return Result.ok(roleRepository.findAll()); }

    @PostMapping("/roles")
    public Result<AdminRole> saveRole(@RequestBody Map<String, Object> body) {
        AdminRole role = body.get("id") == null ? new AdminRole() : roleRepository.findById(Long.parseLong(body.get("id").toString())).orElseThrow();
        role.setCode(String.valueOf(body.getOrDefault("code", "")));
        role.setName(String.valueOf(body.getOrDefault("name", "")));
        role.setEnabled(Integer.parseInt(String.valueOf(body.getOrDefault("enabled", 1))));
        AdminRole saved = roleRepository.save(role);
        auditService.record("SYSTEM_ROLE_SAVE", "roleId=" + saved.getId());
        return Result.ok(saved);
    }

    @PostMapping("/roles/{roleId}/menus")
    public Result<Void> assignMenus(@PathVariable long roleId, @RequestBody Map<String, Object> body) {
        roleMenuRepository.deleteAll(roleMenuRepository.findByRoleId(roleId));
        Object raw = body.get("menuIds");
        if (raw instanceof List<?> ids) for (Object id : ids) {
            AdminRoleMenu link = new AdminRoleMenu();
            link.setRoleId(roleId);
            link.setMenuId(Long.parseLong(String.valueOf(id)));
            roleMenuRepository.save(link);
        }
        auditService.record("SYSTEM_ROLE_MENU_ASSIGN", "roleId=" + roleId);
        return Result.ok();
    }

    @GetMapping("/users")
    public Result<List<Map<String, Object>>> users() {
        return Result.ok(userRepository.findAll().stream().map(u -> Map.<String, Object>of(
                "id", u.getId(), "mobile", u.getMobile() == null ? "" : u.getMobile(), "nickname", u.getNickname(), "role", u.getRole(), "status", u.getStatus(),
                "roleIds", userRoleRepository.findByUserId(u.getId()).stream().map(AdminUserRole::getRoleId).toList())).toList());
    }

    @PostMapping("/users/{userId}/roles")
    public Result<Void> assignUserRoles(@PathVariable long userId, @RequestBody Map<String, Object> body) {
        userRepository.findById(userId).orElseThrow();
        userRoleRepository.deleteByUserId(userId);
        Object raw = body.get("roleIds");
        if (raw instanceof List<?> ids) for (Object id : ids) {
            AdminUserRole link = new AdminUserRole();
            link.setUserId(userId);
            link.setRoleId(Long.parseLong(String.valueOf(id)));
            userRoleRepository.save(link);
        }
        auditService.record("SYSTEM_USER_ROLE_ASSIGN", "userId=" + userId);
        return Result.ok();
    }

    @PostMapping("/users/{userId}/status")
    public Result<Void> changeUserStatus(@PathVariable long userId, @RequestBody Map<String, Integer> body) {
        User user = userRepository.findById(userId).orElseThrow();
        user.setStatus(body.getOrDefault("status", 1));
        userRepository.save(user);
        auditService.record("SYSTEM_USER_STATUS", "userId=" + userId + ", status=" + user.getStatus());
        return Result.ok();
    }

    @PostMapping("/users")
    public Result<?> createAdmin(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || password == null || password.length() < 8) return Result.fail(400, "账号和至少8位密码必填");
        if (accountRepository.findByUsernameAndEnabled(username, 1).isPresent()) return Result.fail(409, "账号已存在");
        User user = new User();
        user.setMobile(username);
        user.setNickname(body.getOrDefault("nickname", username));
        user.setRole("ADMIN");
        user.setStatus(1);
        user = userRepository.save(user);
        AdminAccount account = new AdminAccount();
        account.setUserId(user.getId());
        account.setUsername(username);
        account.setPasswordHash(PasswordUtil.sha256(password));
        account.setEnabled(1);
        accountRepository.save(account);
        auditService.record("SYSTEM_ADMIN_CREATE", "userId=" + user.getId());
        return Result.ok();
    }
}
