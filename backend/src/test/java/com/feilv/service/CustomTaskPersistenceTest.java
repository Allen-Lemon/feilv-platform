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
import java.util.Arrays;
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
    @SpyBean TaskTagRepository tags;
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
        User user = user("13800138201", "USER"); TaskTag tag = new TaskTag(); tag.setName("任务标签"); tag.setTaskModes("ONSITE"); tag = tags.saveAndFlush(tag);
        long tagId = tag.getId();
        long orderBefore = orders.count(), taskBefore = tasks.count(), tagsBefore = snapshots.count(), feesBefore = fees.count();
        doThrow(new IllegalStateException("snapshot-write-failed")).when(snapshots).save(any(CustomTaskTag.class));
        try {
            assertThrows(IllegalStateException.class, () -> service.publish(user.getId(), user.getId(), "MINIAPP", request(List.of(tagId))));
            assertEquals(orderBefore, orders.count()); assertEquals(taskBefore, tasks.count()); assertEquals(tagsBefore, snapshots.count()); assertEquals(feesBefore, fees.count());
            verifyNoInteractions(pricing);
        } finally { reset(snapshots); }
    }

    @Test void tagModesPersistCanonicallyForEveryNonEmptySelection() {
        List<String> modes = List.of("ONSITE", "PART_TIME", "ONLINE", "RIDE", "DRIVING");
        for (int mask = 1; mask < (1 << modes.size()); mask++) {
            var selected = new java.util.ArrayList<String>();
            var expected = new java.util.ArrayList<String>();
            for (int i = 0; i < modes.size(); i++) if ((mask & (1 << i)) != 0) expected.add(modes.get(i));
            for (int i = modes.size() - 1; i >= 0; i--) if ((mask & (1 << i)) != 0) selected.add(modes.get(i));
            var created = labels.save(null, new TagReq("多选标签" + mask, true, mask, null, selected));
            assertEquals(expected, created.taskModes());
            assertEquals(String.join(",", expected), tags.findById(created.id()).orElseThrow().getTaskModes());
            assertEquals(expected, labels.list(true).stream().filter(t -> t.id() == created.id()).findFirst().orElseThrow().taskModes());
        }
    }

    @Test void invalidAndLegacyJavaRequestsCannotCreateOrUpdateClassification() {
        var label = labels.save(null, new TagReq("保留分类", true, 0, null, List.of("ONSITE")));
        long count = tags.count();
        List<List<String>> invalid = Arrays.asList(null, List.of(), List.of("DELIVERY"), List.of("online"), List.of(" ONLINE"),
                List.of("ONLINE", "ONLINE"), Arrays.asList("ONLINE", null), Arrays.asList((String) null),
                List.of("ONSITE", "PART_TIME", "ONLINE", "RIDE", "DRIVING", "ONSITE"));
        for (List<String> modes : invalid) {
            assertEquals(400, assertThrows(BizException.class, () -> labels.save(null, new TagReq("非法新建", true, 0, null, modes))).getCode());
            assertEquals(400, assertThrows(BizException.class, () -> labels.save(label.id(), new TagReq("非法修改", false, 1, label.version(), modes))).getCode());
        }
        assertEquals(400, assertThrows(BizException.class, () -> labels.save(null, new TagReq("旧构造器", true, 0, null))).getCode());
        assertEquals(400, assertThrows(BizException.class, () -> labels.save(label.id(), new TagReq("旧构造器", false, 1, label.version()))).getCode());
        assertEquals(count, tags.count());
        var unchanged = tags.findById(label.id()).orElseThrow();
        assertEquals("保留分类", unchanged.getName()); assertTrue(unchanged.isEnabled()); assertEquals(label.version(), unchanged.getVersion());
        assertEquals("ONSITE", unchanged.getTaskModes());
        var updated = labels.save(label.id(), new TagReq("重新分类", true, 0, label.version(), List.of("ONLINE", "PART_TIME")));
        assertEquals(List.of("PART_TIME", "ONLINE"), updated.taskModes());
        assertEquals("PART_TIME,ONLINE", tags.findById(label.id()).orElseThrow().getTaskModes());
        assertEquals(409, assertThrows(BizException.class, () -> labels.save(label.id(), new TagReq("过期修改", true, 0, label.version(), List.of("ONSITE")))).getCode());
    }

    @Test void publicListHidesUnclassifiedLegacyTagsUntilExplicitlyClassified() {
        var legacy = new java.util.ArrayList<TaskTag>();
        for (String value : Arrays.asList(null, "", "  ")) {
            TaskTag tag = new TaskTag(); tag.setName("历史待分类"); tag.setTaskModes(value); legacy.add(tags.saveAndFlush(tag));
        }
        for (TaskTag tag : legacy) {
            assertTrue(labels.list(true).stream().noneMatch(t -> t.id() == tag.getId()));
            assertEquals(List.of(), labels.list(false).stream().filter(t -> t.id() == tag.getId()).findFirst().orElseThrow().taskModes());
        }
        TaskTag first = legacy.get(0);
        labels.save(first.getId(), new TagReq(first.getName(), true, first.getSort(), first.getVersion(), List.of("ONLINE")));
        assertEquals(List.of("ONLINE"), labels.list(true).stream().filter(t -> t.id() == first.getId()).findFirst().orElseThrow().taskModes());
    }

    @Test void publishRejectsMismatchedUnclassifiedOrInvalidTagModesBeforeAnyOrderWrite() {
        User user = user("13800138203", "USER");
        long orderBefore = orders.count(), taskBefore = tasks.count(), snapshotsBefore = snapshots.count(), feesBefore = fees.count();
        for (String value : Arrays.asList(null, "", "ONLINE", "PART_TIME", "NOT_ONSITE", "ONSITE,ONSITE", "ONSITE,")) {
            TaskTag tag = new TaskTag(); tag.setName("不可用于上门"); tag.setTaskModes(value); tag = tags.saveAndFlush(tag);
            long id = tag.getId();
            assertEquals(409, assertThrows(BizException.class, () -> service.publish(user.getId(), user.getId(), "MINIAPP", request(List.of(id)))).getCode());
        }
        assertEquals(orderBefore, orders.count()); assertEquals(taskBefore, tasks.count()); assertEquals(snapshotsBefore, snapshots.count()); assertEquals(feesBefore, fees.count());
        verifyNoInteractions(pricing);
    }

    @Test void ridePublicationPersistsFixedTitleDefaultContentAndNoTagDependency() {
        User user = user("13800138204", "USER");
        clearInvocations(tags, snapshots);
        for (String mode : List.of("RIDE", "DRIVING")) {
            String title = "RIDE".equals(mode) ? "接送服务" : "代驾服务";
            for (String note : Arrays.asList(null, "  \n\t ", "  带一个行李箱  ")) {
                var request = new PublishReq("客户端伪装标题", note, null, mode, 1, "  联系人  ", " 13800138000 ", "  唯一起点  ", "  今天18:30  ");
                var created = service.publish(user.getId(), user.getId(), "MINIAPP", request);
                var task = tasks.findByOrderId(created.id()).orElseThrow();
                assertEquals(mode, task.getTaskMode()); assertEquals(title, task.getTitle()); assertEquals(title, created.goodsDesc());
                assertEquals(note == null || note.isBlank() ? title + "，请按约定时间到起始地点提供服务。" : note.strip(), task.getContent());
                assertEquals("唯一起点", task.getLocationAddress()); assertEquals("今天18:30", task.getServiceTime());
                assertEquals("联系人", task.getContact()); assertEquals("13800138000", task.getPhone());
                var order = orders.findById(created.id()).orElseThrow();
                assertEquals("CUSTOM", order.getOrderType()); assertEquals(1, order.getRealFee());
                assertEquals("唯一起点", order.getFromAddress()); assertEquals(order.getFromAddress(), order.getToAddress());
                assertNull(order.getFromLng()); assertNull(order.getToLng()); assertEquals("", order.getPickupCode());
            }
        }
        verifyNoInteractions(tags, pricing);
        verify(snapshots, never()).save(any(CustomTaskTag.class));
    }

    @Test void rideServiceRejectsNonemptyTagsEvenWhenMatchingBeforeOrderWrites() {
        User user = user("13800138205", "USER");
        var label = labels.save(null, new TagReq("接送标签", true, 0, null, List.of("RIDE", "DRIVING")));
        long orderBefore = orders.count(), taskBefore = tasks.count(), feesBefore = fees.count(), snapshotBefore = snapshots.count();
        clearInvocations(tags);
        for (String mode : List.of("RIDE", "DRIVING")) {
            var request = new PublishReq(null, null, List.of(label.id()), mode, 1000, "甲", "13800138000", "起点", "明天18点");
            assertEquals(400, assertThrows(BizException.class, () -> service.publish(user.getId(), user.getId(), "MINIAPP", request)).getCode());
        }
        assertEquals(orderBefore, orders.count()); assertEquals(taskBefore, tasks.count()); assertEquals(feesBefore, fees.count()); assertEquals(snapshotBefore, snapshots.count());
        verifyNoInteractions(tags, pricing);
    }

    @Test void riderPublisherGetsOwnerFlagAndTagNameSnapshotSurvivesDisable() {
        snapshots.deleteAll(); tasks.deleteAll(); orders.deleteAll(); tags.deleteAll();
        User user = user("13800138202", "RIDER");
        var label = labels.save(null, new TagReq("标签".repeat(16), true, 0, null, List.of("ONSITE")));
        try {
            UserContext.set(new UserContext.CurrentUser(user.getId(), "RIDER"));
            var order = service.publish(user.getId(), user.getId(), "MINIAPP", request(List.of(label.id())));
            assertTrue(order.owner()); assertEquals("自定义测试", order.goodsDesc()); assertEquals("ONSITE", order.taskMode());
            assertEquals(12345, order.estimateFeeFen()); verifyNoInteractions(pricing);
            labels.save(label.id(), new TagReq("已停用", false, 0, label.version(), List.of("ONLINE")));
            assertEquals("标签".repeat(16), service.detail(order.id(), user.getId()).tags().get(0).name());
            assertEquals(0, labels.list(true).size());
            assertEquals(400, assertThrows(BizException.class, () -> labels.save(null, new TagReq("😀".repeat(33), true, 0, null, List.of("ONSITE")))).getCode());
        } finally { UserContext.clear(); }
    }
}
