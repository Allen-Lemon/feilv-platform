package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.common.UserContext;
import com.feilv.domain.*;
import com.feilv.dto.ApiDtos.OrderCreateReq;
import com.feilv.dto.ApiDtos.QuoteVO;
import com.feilv.dto.CustomTaskDtos.*;
import com.feilv.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false, properties = {"spring.sql.init.mode=never", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"})
@Import({CustomTaskService.class, TaskTagService.class, OrderService.class, UserProfileService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CustomTaskPersistenceTest {
    @Autowired CustomTaskService service;
    @Autowired TaskTagService labels;
    @Autowired UserRepository users;
    @Autowired TaskTagRepository tags;
    @Autowired CustomTaskRepository tasks;
    @Autowired OrderRepository orders;
    @Autowired OrderFeeDetailRepository fees;
    @SpyBean CustomTaskTagRepository snapshots;
    @MockBean PricingService pricing;
    @MockBean BanWordService banWords;
    @MockBean MessageService messages;
    @MockBean AuditService audit;
    @MockBean WechatPhoneClient phone;
    @MockBean WechatConfigService settings;
    @MockBean WalletAccountingService wallet;

    private User user(String mobile, String role) { User user = new User(); user.setMobile(mobile); user.setRole(role); return users.saveAndFlush(user); }
    private PublishReq request(List<Long> tagIds) {
        return new PublishReq("自定义测试", "完整任务内容", tagIds, "ONSITE", 12345, "甲", "13800138000", "服务地点", "明天下午");
    }

    @Test void textLimitsCountCodepointsNotUtf16Units() {
        // H2 VARCHAR counts UTF-16 units unlike MySQL utf8mb4; validate supplementary boundaries without persistence.
        for (int max : List.of(32, 64, 100, 255, 1000)) {
            String text = "😀".repeat(max);
            assertEquals(text, TaskTagService.text(text, max, "任务字段"));
            assertEquals(400, assertThrows(BizException.class, () -> TaskTagService.text(text + "😀", max, "任务字段")).getCode());
        }
    }

    @Test void snapshotFailureRollsBackOrderTaskFeesAndLeavesHistoricalDataUntouched() {
        User user = user("13800138201", "USER"); TaskTag tag = new TaskTag(); tag.setName("任务标签"); tag = tags.saveAndFlush(tag);
        long orderBefore = orders.count(), taskBefore = tasks.count(), tagsBefore = snapshots.count(), feesBefore = fees.count();
        doThrow(new IllegalStateException("snapshot-write-failed")).when(snapshots).save(any(CustomTaskTag.class));
        try {
            assertThrows(IllegalStateException.class, () -> service.publish(user.getId(), user.getId(), "MINIAPP", request(List.of(tags.findAll().get(0).getId()))));
            assertEquals(orderBefore, orders.count()); assertEquals(taskBefore, tasks.count()); assertEquals(tagsBefore, snapshots.count()); assertEquals(feesBefore, fees.count());
            verifyNoInteractions(pricing);
        } finally { reset(snapshots); }
    }

    @Test void riderPublisherGetsOwnerFlagAndTagNameSnapshotSurvivesDisable() {
        snapshots.deleteAll(); tasks.deleteAll(); orders.deleteAll(); tags.deleteAll();
        User user = user("13800138202", "RIDER");
        var label = labels.save(null, new TagReq("标签".repeat(16), true, 0, null));
        try {
            UserContext.set(new UserContext.CurrentUser(user.getId(), "RIDER"));
            var order = service.publish(user.getId(), user.getId(), "MINIAPP", request(List.of(label.id())));
            assertTrue(order.owner()); assertEquals("自定义测试", order.goodsDesc()); assertEquals("ONSITE", order.taskMode());
            assertEquals(12345, order.estimateFeeFen()); verifyNoInteractions(pricing);
            labels.save(label.id(), new TagReq("已停用", false, 0, label.version()));
            assertEquals("标签".repeat(16), service.detail(order.id(), user.getId()).tags().get(0).name());
            assertEquals(0, labels.list(true).size());
            assertEquals(400, assertThrows(BizException.class, () -> labels.save(null, new TagReq("😀".repeat(33), true, 0, null))).getCode());
        } finally { UserContext.clear(); }
    }
}
