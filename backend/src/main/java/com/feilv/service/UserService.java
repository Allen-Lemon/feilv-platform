package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.AdminAccount;
import com.feilv.domain.Rider;
import com.feilv.domain.User;
import com.feilv.dto.ApiDtos.AdminMenuVO;
import com.feilv.dto.ApiDtos.AdminProfileVO;
import com.feilv.dto.ApiDtos.LoginVO;
import com.feilv.repository.AdminAccountRepository;
import com.feilv.repository.AdminMenuRepository;
import com.feilv.repository.AdminRoleMenuRepository;
import com.feilv.repository.AdminRoleRepository;
import com.feilv.repository.AdminUserRoleRepository;
import com.feilv.repository.RiderRepository;
import com.feilv.repository.UserRepository;
import com.feilv.util.JwtUtil;
import com.feilv.util.PasswordUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户域（对应功能设计文档 4.1/8.1）：
 * 验证码（60秒冷却、60秒有效期）→ 注册登录（手机号主键，未注册自动注册）。
 * 演示模式：验证码直接返回，生产须只经短信下发且不回传客户端。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private static final long CODE_TTL_SEC = 60;
    private static final long SEND_COOLDOWN_SEC = 60;
    private static final int DAILY_LIMIT = 10;

    private final UserRepository userRepository;
    private final RiderRepository riderRepository;
    private final MessageService messageService;
    private final AdminAccountRepository adminAccountRepository;
    private final AdminRoleRepository adminRoleRepository;
    private final AdminMenuRepository adminMenuRepository;
    private final AdminUserRoleRepository adminUserRoleRepository;
    private final AdminRoleMenuRepository adminRoleMenuRepository;

    /** mobile -> 验证码与过期时间 */
    private final Map<String, CodeEntry> codeStore = new ConcurrentHashMap<>();
    /** mobile -> 上次发送时间（冷却控制） */
    private final Map<String, Long> sendCooling = new ConcurrentHashMap<>();
    /** mobile -> 当日发送次数 */
    private final Map<String, DailyCounter> dailySendCounts = new ConcurrentHashMap<>();
    /** mobile -> 当前验证码连续错误次数 */
    private final Map<String, Integer> codeErrorCounts = new ConcurrentHashMap<>();

    private record DailyCounter(String day, int count) {}

    private record CodeEntry(String code, LocalDateTime expireAt) {}

    /** 发送验证码：60秒冷却、60秒有效期 */
    public String sendSmsCode(String mobile) {
        BanWordService.checkMobile(mobile);
        long now = System.currentTimeMillis();
        String today = java.time.LocalDate.now().toString();
        DailyCounter current = dailySendCounts.get(mobile);
        if (current != null && today.equals(current.day()) && current.count() >= DAILY_LIMIT) {
            throw BizException.of(4292, "今日验证码发送次数已达上限");
        }
        Long last = sendCooling.get(mobile);
        if (last != null && now - last < SEND_COOLDOWN_SEC * 1000) {
            throw BizException.of(4291, "操作频繁，请60秒后再试");
        }
        String code = String.valueOf(100000 + (int) (Math.random() * 900000));
        codeStore.put(mobile, new CodeEntry(code, LocalDateTime.now().plusSeconds(CODE_TTL_SEC)));
        sendCooling.put(mobile, now);
        DailyCounter previous = dailySendCounts.get(mobile);
        dailySendCounts.put(mobile, previous == null || !today.equals(previous.day())
                ? new DailyCounter(today, 1) : new DailyCounter(today, previous.count() + 1));
        codeErrorCounts.remove(mobile);
        messageService.sendSms(mobile, "【飞毛腿】验证码：" + code + "，有效期60秒。");
        return code; // 演示模式回传，生产环境改为下发短信并返回空
    }

    private void verifyCode(String mobile, String code) {
        CodeEntry entry = codeStore.get(mobile);
        if (entry == null || LocalDateTime.now().isAfter(entry.expireAt())) {
            throw BizException.of(4010, "验证码已过期，请重新获取");
        }
        if (!entry.code().equals(code)) {
            int errors = codeErrorCounts.merge(mobile, 1, Integer::sum);
            if (errors >= 5) {
                codeStore.remove(mobile);
                codeErrorCounts.remove(mobile);
                throw BizException.of(4011, "验证码错误次数过多，请重新获取");
            }
            throw BizException.of(4011, "验证码错误");
        }
        codeStore.remove(mobile); // 一次一密，验证即失效
        codeErrorCounts.remove(mobile);
    }

    /** 手机号登录：未注册自动注册（对应详细设计4.1.1） */
    @Transactional
    public LoginVO login(String mobile, String code) {
        BanWordService.checkMobile(mobile);
        verifyCode(mobile, code);
        User user = userRepository.findByMobile(mobile)
                .orElseGet(() -> {
                    User u = new User();
                    u.setMobile(mobile);
                    u.setNickname("用户" + mobile.substring(mobile.length() - 4));
                    u.setRole("USER");
                    return userRepository.save(u);
                });
        if (user.getStatus() == 0) throw BizException.of(4030, "账户已冻结，请联系客服");
        return new LoginVO(JwtUtil.create(user.getId(), user.getRole()), user.getId(), user.getRole(), user.getNickname());
    }

    /** 骑手注册（对应详细设计5.1）：验证码校验 → 创建骑手档案（演示即时审核通过） */
    @Transactional
    public LoginVO registerRider(String mobile, String name, String code, String plateNo) {
        BanWordService.checkMobile(mobile);
        verifyCode(mobile, code);
        User user = userRepository.findByMobile(mobile).orElseGet(() -> {
            User u = new User();
            u.setMobile(mobile);
            u.setNickname(name);
            u.setRole("RIDER");
            return userRepository.save(u);
        });
        user.setRole("RIDER");
        userRepository.save(user);
        if (riderRepository.findByUserId(user.getId()).isEmpty()) {
            Rider r = new Rider();
            r.setUserId(user.getId());
            r.setName(name);
            r.setPlateNo(plateNo == null ? "" : plateNo);
            r.setStatus("ACTIVE"); // 演示跳过人工审核；生产：PENDING→人工审核≤1个工作日→培训考试≥80分
            riderRepository.save(r);
            log.info("骑手入驻：mobile={}, name={}", mobile, name);
        }
        return new LoginVO(JwtUtil.create(user.getId(), "RIDER"), user.getId(), "RIDER", name);
    }

    /** 管理后台登录；账号、密码摘要、角色和菜单均从数据库读取。 */
    @Transactional(readOnly = true)
    public LoginVO adminLogin(String username, String password) {
        AdminAccount account = adminAccountRepository.findByUsernameAndEnabled(username, 1)
                .orElseThrow(() -> BizException.of(4012, "账号或密码错误"));
        if (!PasswordUtil.matches(password, account.getPasswordHash())) {
            throw BizException.of(4012, "账号或密码错误");
        }
        User admin = userRepository.findById(account.getUserId())
                .filter(u -> u.getStatus() == 1 && "ADMIN".equals(u.getRole()))
                .orElseThrow(() -> BizException.of(403, "管理员账号不可用"));
        return new LoginVO(JwtUtil.create(admin.getId(), "ADMIN"), admin.getId(), "ADMIN", admin.getNickname());
    }

    @Transactional(readOnly = true)
    public AdminProfileVO adminProfile(long userId) {
        User admin = userRepository.findById(userId)
                .orElseThrow(() -> BizException.of(404, "用户不存在"));
        List<Long> roleIds = adminUserRoleRepository.findByUserId(userId).stream()
                .map(com.feilv.domain.AdminUserRole::getRoleId).toList();
        List<String> roles = adminRoleRepository.findAllById(roleIds).stream()
                .map(com.feilv.domain.AdminRole::getCode).toList();
        List<Long> menuIds = roleIds.stream().flatMap(roleId -> adminRoleMenuRepository.findByRoleId(roleId).stream())
                .map(com.feilv.domain.AdminRoleMenu::getMenuId).distinct().toList();
        List<AdminMenuVO> menus = adminMenuRepository.findAllById(menuIds).stream()
                .filter(AdminMenuAccess::isAvailableMenu)
                .sorted(java.util.Comparator.comparingInt(com.feilv.domain.AdminMenu::getSort))
                .map(m -> new AdminMenuVO(m.getId(), m.getCode(), m.getName(), m.getPath(), m.getParentId(), m.getSort()))
                .toList();
        String username = adminAccountRepository.findAll().stream()
                .filter(a -> a.getUserId().equals(userId)).map(com.feilv.domain.AdminAccount::getUsername)
                .findFirst().orElse(admin.getMobile());
        return new AdminProfileVO(userId, username, admin.getNickname(), roles, menus);
    }
}
