package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.User;
import com.feilv.dto.AdminUserDtos.UserVO;
import com.feilv.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminUserService {
    private final UserRepository users;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public Page<UserVO> list(int page, String keyword, Integer status, Boolean mobileBound) {
        if (page < 0) throw BizException.of(400, "page必须是从0开始的非负整数");
        validateStatus(status, true);
        String pattern = null, exactMobile = null;
        if (keyword != null && !keyword.isBlank()) {
            String term = keyword.trim();
            pattern = "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            if (term.matches("1[3-9][0-9]{9}")) exactMobile = term;
        }
        return users.findAdminUsers(pattern, exactMobile, status, mobileBound,
                PageRequest.of(page, 15, Sort.by(Sort.Direction.DESC, "createdTime", "id")))
                .map(UserViews::user);
    }

    @Transactional(readOnly = true)
    public UserVO detail(long id) {
        validateId(id);
        return UserViews.user(customer(users.findById(id).orElseThrow(() -> BizException.of(404, "用户不存在"))));
    }

    @Transactional
    public UserVO changeStatus(long id, Integer status) {
        validateId(id);
        validateStatus(status, false);
        // 与手机号绑定/资料写入共用行锁，避免管理状态和用户写入发生丢失更新。
        User user = customer(users.findByIdForUpdate(id).orElseThrow(() -> BizException.of(404, "用户不存在")));
        int previous = user.getStatus();
        user.setStatus(status);
        users.save(user);
        audit.record("USER_STATUS", "userId=" + id + ", oldStatus=" + previous + ", status=" + status);
        return UserViews.user(user);
    }

    private User customer(User user) {
        if (!"USER".equals(user.getRole()) && !"RIDER".equals(user.getRole())) {
            throw BizException.of(403, "不可通过C端用户接口访问或修改此账号");
        }
        return user;
    }

    private void validateStatus(Integer status, boolean optional) {
        if (status == null && optional) return;
        if (status == null || status < 0 || status > 1) throw BizException.of(400, "status必须是0或1");
    }

    private void validateId(long id) {
        if (id <= 0) throw BizException.of(400, "用户ID必须是正整数");
    }
}
