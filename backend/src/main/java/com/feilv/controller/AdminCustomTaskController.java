package com.feilv.controller;

import com.feilv.common.*;
import com.feilv.dto.ApiDtos.QuoteVO;
import com.feilv.dto.CustomTaskDtos.*;
import com.feilv.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminCustomTaskController {
    private final AdminMenuAccess access;
    private final CustomTaskService tasks;
    private final TaskTagService tags;

    @GetMapping("/task-tags")
    public Result<List<TagVO>> tags() { access.requireMenu("tasks"); return Result.ok(tags.list(false)); }
    @PostMapping("/task-tags")
    public Result<TagVO> addTag(@Valid @RequestBody TagReq req) { access.requireMenu("tasks"); return Result.ok(tags.save(null, req)); }
    @PutMapping("/task-tags/{id}")
    public Result<TagVO> updateTag(@PathVariable long id, @Valid @RequestBody TagReq req) { access.requireMenu("tasks"); return Result.ok(tags.save(id, req)); }

    @GetMapping("/custom-tasks")
    public Result<Page<TaskVO>> list(@RequestParam(defaultValue = "0") String page, @RequestParam(required = false) String userId,
                                      @RequestParam(required = false) String status) {
        access.requireMenu("tasks");
        return Result.ok(tasks.list(AdminQueryParameters.page(page), userId == null ? null : AdminQueryParameters.id(userId), AdminQueryParameters.orderStatus(status)));
    }

    @PostMapping("/custom-tasks/quote")
    public Result<QuoteVO> quote(@Valid @RequestBody QuoteReq req) { access.requireMenu("tasks"); return Result.ok(tasks.quote(req)); }

    @PostMapping("/custom-tasks")
    @Transactional
    public Result<TaskVO> publish(@Valid @RequestBody AdminPublishReq req) {
        access.requireMenu("tasks");
        var order = tasks.publish(req.userId(), UserContext.userId(), "ADMIN", new PublishReq(req.title(), req.content(), req.tagIds(), req.order()));
        return Result.ok(tasks.detail(order.id(), null));
    }

    @GetMapping("/custom-tasks/{orderId}")
    public Result<TaskVO> detail(@PathVariable long orderId) { access.requireMenu("tasks"); return Result.ok(tasks.detail(orderId, null)); }
}
