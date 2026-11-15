package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.*;
import com.feilv.dto.ApiDtos.OrderCreateReq;
import com.feilv.dto.ApiDtos.OrderVO;
import com.feilv.dto.ApiDtos.QuoteVO;
import com.feilv.dto.CustomTaskDtos.*;
import com.feilv.enums.OrderStatus;
import com.feilv.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CustomTaskService {
    private final CustomTaskRepository tasks;
    private final CustomTaskTagRepository snapshots;
    private final TaskTagRepository tags;
    private final OrderRepository orders;
    private final UserRepository users;
    private final OrderService orderService;
    private final PricingService pricing;
    private final BanWordService banWords;
    private final AuditService audit;

    public QuoteVO quote(QuoteReq req) {
        validateMeasure(req.distanceM(), req.weightKg());
        return pricing.quote(req.distanceM(), req.weightKg());
    }

    @Transactional
    public OrderVO publish(long userId, long operatorId, String source, PublishReq req) {
        User user = users.findByIdForUpdate(userId).orElseThrow(() -> BizException.of(404, "发布用户不存在"));
        if (user.getStatus() != 1 || !Set.of("USER", "RIDER").contains(user.getRole())) throw BizException.of(403, "此用户不可发布任务");
        String title = TaskTagService.text(req.title(), 64, "任务标题");
        String content = TaskTagService.text(req.content(), 1000, "任务内容");
        banWords.checkGoods(title + " " + content);
        if (req.order() == null || !"CUSTOM".equals(req.order().orderType())) throw BizException.of(400, "请使用CUSTOM订单类型");
        var order = normalizeOrder(req.order(), title);
        List<Long> ids = req.tagIds() == null ? List.of() : req.tagIds();
        if (ids.size() > 5 || ids.stream().anyMatch(id -> id == null || id <= 0) || new HashSet<>(ids).size() != ids.size()) throw BizException.of(400, "请选择最多5个不同标签");
        List<TaskTag> chosen = new ArrayList<>();
        // 固定顺序锁定标签，与停用/改名互斥；发布保存当时的名称快照。
        for (Long id : ids.stream().sorted().toList()) {
            TaskTag tag = tags.findByIdForUpdate(id).filter(TaskTag::isEnabled).orElseThrow(() -> BizException.of(409, "标签已停用或不存在，请刷新标签"));
            chosen.add(tag);
        }
        OrderVO result = orderService.createCustom(userId, order);
        CustomTask task = new CustomTask(); task.setOrderId(result.id()); task.setUserId(userId);
        task.setTitle(title); task.setContent(content); task.setPublishSource(source); task.setCreatedBy(operatorId);
        task = tasks.saveAndFlush(task);
        for (TaskTag tag : chosen) {
            CustomTaskTag snapshot = new CustomTaskTag(); snapshot.setTaskId(task.getId()); snapshot.setTagId(tag.getId()); snapshot.setTagName(tag.getName()); snapshots.save(snapshot);
        }
        audit.record("CUSTOM_TASK_PUBLISH", "taskId=" + task.getId() + ", orderId=" + result.id() + ", userId=" + userId + ", source=" + source);
        return result;
    }

    @Transactional(readOnly = true)
    public TaskVO detail(long orderId, Long participantId) {
        if (orderId <= 0) throw BizException.of(400, "订单ID必须为正整数");
        OrderMain order = orders.findById(orderId).orElseThrow(() -> BizException.of(404, "订单不存在"));
        if (participantId != null && !orderService.isOrderParticipant(participantId, order)) throw BizException.of(403, "无权查看此任务");
        CustomTask task = tasks.findByOrderId(orderId).orElse(null);
        if (task == null) return null;
        var tags = snapshots.findByTaskIdInOrderByIdAsc(List.of(task.getId())).stream().map(t -> new TagSnapshot(t.getTagId(), t.getTagName())).toList();
        return view(task, order, tags);
    }

    @Transactional(readOnly = true)
    public Page<TaskVO> list(int page, Long userId, OrderStatus status) {
        if (page < 0 || userId != null && userId <= 0) throw BizException.of(400, "分页或用户ID不合法");
        var result = tasks.findAdminTasks(userId, status, PageRequest.of(page, 15, Sort.by(Sort.Direction.DESC, "id")));
        var orderMap = orders.findAllById(result.map(CustomTask::getOrderId).toList()).stream().collect(Collectors.toMap(OrderMain::getId, o -> o));
        var tagMap = snapshots.findByTaskIdInOrderByIdAsc(result.map(CustomTask::getId).toList()).stream().collect(Collectors.groupingBy(CustomTaskTag::getTaskId));
        return result.map(task -> view(task, orderMap.get(task.getOrderId()), tagMap.getOrDefault(task.getId(), List.of()).stream().map(t -> new TagSnapshot(t.getTagId(), t.getTagName())).toList()));
    }

    private TaskVO view(CustomTask task, OrderMain order, List<TagSnapshot> labels) {
        if (order == null) throw BizException.of(409, "任务关联订单不存在，请联系客服");
        return new TaskVO(task.getId(), order.getId(), order.getOrderNo(), order.getStatus().name(), order.getStatus().label(),
                task.getUserId(), task.getTitle(), task.getContent(), labels, task.getPublishSource(), task.getCreatedBy(), task.getCreatedTime(), order.getRealFee(), order.getFromAddress(), order.getToAddress());
    }

    private OrderCreateReq normalizeOrder(OrderCreateReq req, String title) {
        validateMeasure(req.distanceM(), req.weightKg());
        String fromPhone = phone(req.fromPhone()), toPhone = phone(req.toPhone());
        String remark = req.remark() == null ? "" : req.remark().strip();
        if (remark.codePointCount(0, remark.length()) > 255) throw BizException.of(400, "备注不能超过255个字符");
        return new OrderCreateReq("CUSTOM", TaskTagService.text(req.fromContact(), 32, "取件联系人"), fromPhone,
                TaskTagService.text(req.fromAddress(), 255, "取件地址"), req.fromLng(), req.fromLat(),
                TaskTagService.text(req.toContact(), 32, "收件联系人"), toPhone, TaskTagService.text(req.toAddress(), 255, "送达地址"),
                req.toLng(), req.toLat(), req.distanceM(), req.weightKg(), title, remark);
    }

    private static void validateMeasure(Integer distance, Integer weight) {
        if (distance == null || distance < 1 || distance > 200000 || weight == null || weight < 1 || weight > 100) throw BizException.of(400, "距离须为1至200000米，重量须为1至100公斤");
    }
    private static String phone(String value) {
        String result = value == null ? "" : value.strip();
        if (!result.matches("1[3-9][0-9]{9}")) throw BizException.of(400, "请填写有效联系电话");
        return result;
    }
}
