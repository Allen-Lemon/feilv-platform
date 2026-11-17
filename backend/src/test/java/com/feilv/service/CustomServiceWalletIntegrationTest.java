package com.feilv.service;

import com.feilv.common.UserContext;
import com.feilv.domain.*;
import com.feilv.dto.ComplaintDtos.ComplaintReviewReq;
import com.feilv.dto.CustomTaskDtos.PublishReq;
import com.feilv.dto.CustomTaskDtos.QuoteReq;
import com.feilv.enums.OrderStatus;
import com.feilv.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** 新用户定价服务从发布到MOCK支付/骑手履约/钱包和投诉的实际集成，不扫描历史订单。 */
@SpringBootTest(properties = {com.feilv.controller.TestAuth.TEST_SECRET_PROPERTY,"spring.datasource.url=jdbc:h2:mem:priced-service-wallet;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never"})
class CustomServiceWalletIntegrationTest {
    @Autowired CustomTaskService tasks;
    @Autowired PayService payments;
    @Autowired DispatchService dispatch;
    @Autowired OrderService orders;
    @Autowired ComplaintService complaints;
    @Autowired UserRepository users;
    @Autowired RiderRepository riders;
    @Autowired OrderRepository orderRepository;
    @Autowired PayRecordRepository paymentRepository;
    @Autowired WalletAccountRepository wallets;
    private static final AtomicLong ids = new AtomicLong(1000);

    private User user(String role, String prefix) {
        User user = new User(); user.setRole(role); user.setNickname("服务测试"); user.setMobile(prefix + String.format("%08d", ids.incrementAndGet())); return users.saveAndFlush(user);
    }
    private User rider() {
        User user = user("RIDER", "139"); Rider rider = new Rider(); rider.setUserId(user.getId()); rider.setName("服务承接者"); rider.setOnlineStatus(1); riders.saveAndFlush(rider); return user;
    }

    @Test void millionYuanOnlineTaskKeepsExactPriceAndPaysOriginalShareOnlyAfterReceipt() {
        User publisher = user("USER", "138"), contractor = rider();
        var quote = tasks.quote(new QuoteReq(100000000));
        assertEquals(100000000, quote.totalFen()); assertEquals(70000000, quote.riderShareFen()); assertEquals(30000000, quote.platformShareFen());
        var order = tasks.publish(publisher.getId(), publisher.getId(), "MINIAPP", new PublishReq("线上资料整理", "按约定线上交付整理结果", List.of(), "ONLINE", 100000000, "联系人", "13800138000", "不应保留的旧地址", ""));
        assertEquals(100000000, order.estimateFeeFen()); assertEquals("ONLINE", order.taskMode()); assertEquals("", order.pickupCode());
        assertEquals("", tasks.detail(order.id(), publisher.getId()).locationAddress());
        var payment = payments.pay(publisher.getId(), order.id()); assertEquals(70000000, payment.getShareRider()); assertEquals(30000000, payment.getSharePlatform());
        assertEquals("", dispatch.grab(contractor.getId(), order.id()));
        dispatch.serviceStart(contractor.getId(), order.id()); dispatch.serviceComplete(contractor.getId(), order.id()); dispatch.serviceComplete(contractor.getId(), order.id());
        assertEquals(OrderStatus.DELIVERED, orderRepository.findById(order.id()).orElseThrow().getStatus());
        assertTrue(wallets.findById(contractor.getId()).isEmpty());
        orders.confirm(publisher.getId(), order.id()); orders.confirm(publisher.getId(), order.id());
        assertEquals(70000000, wallets.findById(contractor.getId()).orElseThrow().getAvailableFen());
        assertEquals(100000000, paymentRepository.findByOrderId(order.id()).get(0).getAmount());
    }

    @Test void partTimeTaskUsesOneWorkLocationAndComplaintCompensationComesFromOriginalShare() {
        User publisher = user("USER", "138"), contractor = rider();
        var order = tasks.publish(publisher.getId(), publisher.getId(), "MINIAPP", new PublishReq("搬水泥", "按约定安全搬运，不包含危险作业", List.of(), "PART_TIME", 10000, "负责人", "13800138000", "贵州省铜仁市德江县测试工地", "11月20日 09:00–12:00"));
        payments.pay(publisher.getId(), order.id()); dispatch.grab(contractor.getId(), order.id()); dispatch.serviceStart(contractor.getId(), order.id()); dispatch.serviceComplete(contractor.getId(), order.id());
        var task = tasks.detail(order.id(), contractor.getId()); assertEquals("PART_TIME", task.taskMode()); assertEquals("11月20日 09:00–12:00", task.serviceTime());
        assertEquals("贵州省铜仁市德江县测试工地", task.locationAddress());
        long complaint = complaints.create(publisher.getId(), new com.feilv.dto.ApiDtos.ComplaintReq(order.id(), "OTHER", "服务部分未完成"));
        try { UserContext.set(new UserContext.CurrentUser(publisher.getId(), "ADMIN")); complaints.review(complaint, new ComplaintReviewReq("APPROVED", "核实赔付", 1000)); }
        finally { UserContext.clear(); }
        orders.confirm(publisher.getId(), order.id());
        assertEquals(6000, wallets.findById(contractor.getId()).orElseThrow().getAvailableFen());
        assertEquals(1000, wallets.findById(publisher.getId()).orElseThrow().getAvailableFen());
        assertEquals(7000, paymentRepository.findByOrderId(order.id()).get(0).getShareRider());
    }
}
