package com.feilv.controller;

import com.feilv.common.*;
import com.feilv.dto.ApiDtos.OrderVO;
import com.feilv.dto.CustomTaskDtos.*;
import com.feilv.service.CustomTaskService;
import com.feilv.service.TaskTagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CustomTaskController {
    private final CustomTaskService tasks;
    private final TaskTagService tags;

    @GetMapping("/task-tags")
    public Result<List<TagVO>> tags() { requireCustomer(); return Result.ok(tags.list(true)); }

    @PostMapping("/custom-tasks/quote")
    public Result<QuoteVO> quote(@Valid @RequestBody QuoteReq req) { requireCustomer(); return Result.ok(tasks.quote(req)); }

    @PostMapping("/custom-tasks")
    public Result<OrderVO> publish(@Valid @RequestBody PublishReq req) {
        requireCustomer();
        return Result.ok(tasks.publish(UserContext.userId(), UserContext.userId(), "MINIAPP", req));
    }

    @GetMapping("/orders/{id}/custom-task")
    public Result<TaskVO> detail(@PathVariable long id) { requireCustomer(); return Result.ok(tasks.detail(id, UserContext.userId())); }

    private void requireCustomer() {
        var current = UserContext.get();
        if (current == null || !List.of("USER", "RIDER").contains(current.role())) throw BizException.of(403, "仅用户或骑手账户可访问此功能");
    }
}
