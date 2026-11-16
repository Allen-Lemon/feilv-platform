package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.*;
import com.feilv.dto.ApiDtos.OrderCreateReq;
import com.feilv.dto.ApiDtos.OrderVO;
import com.feilv.dto.ApiDtos.FeeItem;
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
    private final BanWordService banWords;
    private final AuditService audit;

    public QuoteVO quote(QuoteReq req) {
        int price = price(req.priceFen());
        int rider = orderService.estimatedRiderShare(price);
        return new QuoteVO(price, List.of(new FeeItem("CUSTOM", "发布者自定任务总价", price)), rider, price - rider);
    }

    @Transactional
    public OrderVO publish(long userId, long operatorId, String source, PublishReq req) {
        User user = users.findByIdForUpdate(userId).orElseThrow(() -> BizException.of(404, "发布用户不存在"));
        if (user.getStatus() != 1 || !Set.of("USER", "RIDER").contains(user.getRole())) throw BizException.of(403, "此用户不可发布任务");
        String title = TaskTagService.text(req.title(), 64, "任务标题");
        String content = TaskTagService.text(req.content(), 1000, "任务内容");
        banWords.checkGoods(title + " " + content);
        if (req.taskMode() == null || !Set.of("ONLINE", "ONSITE", "PART_TIME").contains(req.taskMode())) throw BizException.of(400, "请选择有效任务场景");
        int price = price(req.priceFen());
        String contact = TaskTagService.text(req.contact(), 32, "联系人");
        String phone = phone(req.phone());
        String location = optionalText(req.locationAddress(), 255, "服务地址");
        String serviceTime = optionalText(req.serviceTime(), 100, "工作时间");
        if (!"ONLINE".equals(req.taskMode()) && location.isBlank()) throw BizException.of(400, "上门或兼职任务必须填写服务地址");
        if ("PART_TIME".equals(req.taskMode()) && serviceTime.isBlank()) throw BizException.of(400, "兼职任务必须填写工作时间");
        if ("ONLINE".equals(req.taskMode())) { location = ""; serviceTime = ""; }
        List<Long> ids = req.tagIds() == null ? List.of() : req.tagIds();
        if (ids.size() > 5 || ids.stream().anyMatch(id -> id == null || id <= 0) || new HashSet<>(ids).size() != ids.size()) throw BizException.of(400, "请选择最多5个不同标签");
        List<TaskTag> chosen = new ArrayList<>();
        // 固定顺序锁定标签，与停用/改名互斥；发布保存当时的名称快照。
        for (Long id : ids.stream().sorted().toList()) {
            TaskTag tag = tags.findByIdForUpdate(id).filter(TaskTag::isEnabled).orElseThrow(() -> BizException.of(409, "标签已停用或不存在，请刷新标签"));
            if (!TaskTagService.taskModes(tag).contains(req.taskMode())) throw BizException.of(409, "标签不适用于当前任务场景，请重新选择标签");
            chosen.add(tag);
        }
        // 兼容订单表非空字段：服务任务只有一个地点，ONLINE不要求地点、不伪造坐标。
        String internalAddress = "ONLINE".equals(req.taskMode()) ? "线上任务，无需到场" : location;
        OrderCreateReq internal = new OrderCreateReq("CUSTOM", contact, phone, internalAddress, null, null,
                contact, phone, internalAddress, null, null, 0, 1, title, "");
        OrderVO result = orderService.createCustom(userId, internal, price);
        CustomTask task = new CustomTask(); task.setOrderId(result.id()); task.setUserId(userId);
        task.setTitle(title); task.setContent(content); task.setPublishSource(source); task.setCreatedBy(operatorId);
        task.setTaskMode(req.taskMode()); task.setPriceFen(price); task.setContact(contact); task.setPhone(phone);
        task.setLocationAddress(location); task.setServiceTime(serviceTime);
        task = tasks.saveAndFlush(task);
        for (TaskTag tag : chosen) {
            CustomTaskTag snapshot = new CustomTaskTag(); snapshot.setTaskId(task.getId()); snapshot.setTagId(tag.getId()); snapshot.setTagName(tag.getName()); snapshots.save(snapshot);
        }
        audit.record("CUSTOM_TASK_PUBLISH", "taskId=" + task.getId() + ", orderId=" + result.id() + ", userId=" + userId + ", source=" + source);
        return orderService.toVO(orderService.mustGet(result.id()), task.getTaskMode());
    }

    @Transactional(readOnly = true)
    public TaskVO detail(long orderId, Long participantId) {
        if (orderId <= 0) throw BizException.of(400, "订单ID必须为正整数");
        OrderMain order = orders.findById(orderId).orElseThrow(() -> BizException.of(404, "订单不存在"));
        // null仅由已检查tasks菜单权限的管理端控制器传入；普通骑手待接单只可查看GrabVO。
        if (participantId != null && !orderService.isOrderParticipant(participantId, order)) throw BizException.of(403, "无权查看此任务");
        CustomTask task = tasks.findByOrderId(orderId).orElse(null);
        if (task == null) return null;
        var labels = snapshots.findByTaskIdInOrderByIdAsc(List.of(task.getId())).stream().map(t -> new TagSnapshot(t.getTagId(), t.getTagName())).toList();
        return view(task, order, labels, orderService.riderShares(List.of(order)).get(orderId));
    }

    @Transactional(readOnly = true)
    public Page<TaskVO> list(int page, Long userId, OrderStatus status) {
        if (page < 0 || userId != null && userId <= 0) throw BizException.of(400, "分页或用户ID不合法");
        var result = tasks.findAdminTasks(userId, status, PageRequest.of(page, 15, Sort.by(Sort.Direction.DESC, "id")));
        var orderList = orders.findAllById(result.map(CustomTask::getOrderId).toList());
        var orderMap = orderList.stream().collect(Collectors.toMap(OrderMain::getId, o -> o));
        var shares = orderService.riderShares(orderList);
        var tagMap = snapshots.findByTaskIdInOrderByIdAsc(result.map(CustomTask::getId).toList()).stream().collect(Collectors.groupingBy(CustomTaskTag::getTaskId));
        return result.map(task -> view(task, orderMap.get(task.getOrderId()), tagMap.getOrDefault(task.getId(), List.of()).stream().map(t -> new TagSnapshot(t.getTagId(), t.getTagName())).toList(), shares.getOrDefault(task.getOrderId(), 0)));
    }

    private TaskVO view(CustomTask task, OrderMain order, List<TagSnapshot> labels, int riderShare) {
        if (order == null) throw BizException.of(409, "任务关联订单不存在，请联系客服");
        int amount = task.getPriceFen() == null ? order.getRealFee() : task.getPriceFen();
        return new TaskVO(task.getId(), order.getId(), order.getOrderNo(), order.getStatus().name(), OrderService.statusLabel(order.getStatus(), OrderService.taskMode(task)),
                task.getUserId(), task.getTitle(), task.getContent(), labels, task.getPublishSource(), task.getCreatedBy(), task.getCreatedTime(),
                amount, order.getFromAddress(), order.getToAddress(), OrderService.taskMode(task), amount,
                task.getContact() == null ? order.getFromContact() : task.getContact(), task.getPhone() == null ? order.getFromPhone() : task.getPhone(),
                task.getLocationAddress(), task.getServiceTime(), riderShare, amount - riderShare);
    }

    private static int price(Integer price) {
        if (price == null || price < 1 || price > 100000000) throw BizException.of(400, "发布价必须为1至100000000分的整数");
        return price;
    }
    private static String optionalText(String value, int max, String label) {
        if (value == null || value.isBlank()) return "";
        return TaskTagService.text(value, max, label);
    }
    private static String phone(String value) {
        String result = value == null ? "" : value.strip();
        if (!result.matches("1[3-9][0-9]{9}")) throw BizException.of(400, "请填写有效联系电话");
        return result;
    }
}
