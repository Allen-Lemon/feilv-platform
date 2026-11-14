package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.User;
import com.feilv.dto.ApiDtos.*;
import com.feilv.repository.OrderRepository;
import com.feilv.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false, properties = {"spring.sql.init.mode=never", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"})
@Import({OrderService.class, UserProfileService.class})
class OrderMobileBindingTest {
    @Autowired OrderService orders;
    @Autowired OrderRepository orderRepository;
    @Autowired UserRepository users;
    @Autowired UserProfileService profiles;
    @MockBean PricingService pricing;
    @MockBean BanWordService banWords;
    @MockBean MessageService messages;
    @MockBean WechatConfigService settings;
    @MockBean WechatPhoneClient phone;

    @Test void unboundDirectApiCallerCannotCreateAnyOrder() {
        User user = users.saveAndFlush(new User());
        var request = request();
        assertEquals(4091, assertThrows(BizException.class, () -> orders.create(user.getId(), request)).getCode());
        assertEquals(0, orderRepository.count());
        verifyNoInteractions(pricing, banWords);
    }

    @Test void boundUserCanCreateOrderAndAvatarPersistsAcrossProfileChanges() {
        User user = new User(); user.setMobile("13800138000");
        user = users.saveAndFlush(user);
        String avatar = "/api/v1/avatars/12345678-1234-1234-1234-123456789abc.png";
        profiles.updateAvatar(user.getId(), avatar);
        profiles.updateNickname(user.getId(), "用户昵称");
        assertEquals(avatar, profiles.me(user.getId()).avatarUrl());
        when(pricing.quote(anyInt(), anyInt())).thenReturn(new QuoteVO(500, List.of()));
        var order = orders.create(user.getId(), request());
        assertNotNull(orderRepository.findById(order.id()).orElse(null));
        assertEquals(user.getId(), orderRepository.findById(order.id()).orElseThrow().getUserId());
        assertEquals(1, orderRepository.count());
    }

    private OrderCreateReq request() {
        return new OrderCreateReq("SEND", "寄件人", "13800138001", "取件地", null, null,
                "收件人", "13800138002", "送达地", null, null, 1000, 1, "文件", "");
    }
}
