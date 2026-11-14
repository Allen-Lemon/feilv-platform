package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.OrderMain;
import com.feilv.domain.User;
import com.feilv.dto.AdminOrderVO;
import com.feilv.enums.OrderStatus;
import com.feilv.repository.OrderRepository;
import com.feilv.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminOrderService {
    private final OrderRepository orders;
    private final UserRepository users;
    private final OrderService orderService;

    @Transactional(readOnly = true)
    public Page<AdminOrderVO> list(int page, Long userId, OrderStatus status) {
        if (page < 0) throw BizException.of(400, "page必须是从0开始的非负整数");
        if (userId != null && userId <= 0) throw BizException.of(400, "用户ID必须是正整数");
        var result = orders.findAdminOrders(userId, status,
                PageRequest.of(page, 15, Sort.by(Sort.Direction.DESC, "createdTime", "id")));
        var ids = result.stream().map(OrderMain::getUserId).filter(Objects::nonNull).distinct().toList();
        Map<Long, User> indexed = ids.isEmpty() ? Map.of() : users.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return result.map(order -> AdminOrderVO.from(orderService.toVO(order), order.getUserId(), indexed.get(order.getUserId())));
    }
}
