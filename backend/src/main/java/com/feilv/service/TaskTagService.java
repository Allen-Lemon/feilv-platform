package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.TaskTag;
import com.feilv.dto.CustomTaskDtos.*;
import com.feilv.repository.TaskTagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskTagService {
    private static final List<String> TASK_MODES = List.of("ONSITE", "PART_TIME", "ONLINE", "RIDE", "DRIVING");
    private final TaskTagRepository tags;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<TagVO> list(boolean activeOnly) {
        return (activeOnly ? tags.findByEnabledTrueOrderBySortAscIdAsc() : tags.findAllByOrderBySortAscIdAsc()).stream()
                .filter(tag -> !activeOnly || !taskModes(tag).isEmpty()).map(this::view).toList();
    }

    @Transactional
    public TagVO save(Long id, TagReq req) {
        String name = text(req.name(), 32, "标签名称");
        if (req.enabled() == null || req.sort() == null || req.sort() < 0 || req.sort() > 100000) throw BizException.of(400, "标签状态或排序不合法");
        String modes = normalizedTaskModes(req.taskModes());
        TaskTag tag = id == null ? new TaskTag() : tags.findByIdForUpdate(id).orElseThrow(() -> BizException.of(404, "标签不存在"));
        if (id != null && (req.version() == null || !req.version().equals(tag.getVersion()))) throw BizException.of(409, "标签已更新，请刷新后重试");
        tag.setName(name); tag.setEnabled(req.enabled()); tag.setSort(req.sort()); tag.setTaskModes(modes);
        tag = tags.saveAndFlush(tag);
        audit.record(id == null ? "TASK_TAG_CREATE" : "TASK_TAG_UPDATE", "tagId=" + tag.getId() + ", enabled=" + tag.isEnabled());
        return view(tag);
    }

    static String text(String value, int max, String label) {
        String result = value == null ? "" : value.strip();
        if (result.isBlank() || result.codePointCount(0, result.length()) > max || result.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')) {
            throw BizException.of(400, label + "须为1至" + max + "个字符");
        }
        return result;
    }

    private static boolean validTaskModes(List<String> modes) {
        return modes != null && !modes.isEmpty() && modes.size() <= TASK_MODES.size()
                && modes.stream().allMatch(mode -> mode != null && TASK_MODES.contains(mode))
                && new HashSet<>(modes).size() == modes.size();
    }

    private static String normalizedTaskModes(List<String> modes) {
        if (!validTaskModes(modes)) throw BizException.of(400, "标签须明确选择1至" + TASK_MODES.size() + "个不同的有效任务场景");
        return String.join(",", TASK_MODES.stream().filter(modes::contains).toList());
    }

    static List<String> taskModes(TaskTag tag) {
        if (tag.getTaskModes() == null || tag.getTaskModes().isBlank()) return List.of();
        List<String> modes = Arrays.asList(tag.getTaskModes().split(",", -1));
        // 未分类或非法存量值不推断归属，也不能用于发布。
        return validTaskModes(modes) ? TASK_MODES.stream().filter(modes::contains).toList() : List.of();
    }

    private TagVO view(TaskTag tag) { return new TagVO(tag.getId(), tag.getName(), tag.isEnabled(), tag.getSort(), tag.getVersion(), taskModes(tag)); }
}
