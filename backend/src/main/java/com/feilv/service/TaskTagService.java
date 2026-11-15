package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.TaskTag;
import com.feilv.dto.CustomTaskDtos.*;
import com.feilv.repository.TaskTagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskTagService {
    private final TaskTagRepository tags;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<TagVO> list(boolean activeOnly) {
        return (activeOnly ? tags.findByEnabledTrueOrderBySortAscIdAsc() : tags.findAllByOrderBySortAscIdAsc()).stream().map(this::view).toList();
    }

    @Transactional
    public TagVO save(Long id, TagReq req) {
        String name = text(req.name(), 32, "标签名称");
        if (req.enabled() == null || req.sort() == null || req.sort() < 0 || req.sort() > 100000) throw BizException.of(400, "标签状态或排序不合法");
        TaskTag tag = id == null ? new TaskTag() : tags.findByIdForUpdate(id).orElseThrow(() -> BizException.of(404, "标签不存在"));
        if (id != null && (req.version() == null || !req.version().equals(tag.getVersion()))) throw BizException.of(409, "标签已更新，请刷新后重试");
        tag.setName(name); tag.setEnabled(req.enabled()); tag.setSort(req.sort());
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

    private TagVO view(TaskTag tag) { return new TagVO(tag.getId(), tag.getName(), tag.isEnabled(), tag.getSort(), tag.getVersion()); }
}
