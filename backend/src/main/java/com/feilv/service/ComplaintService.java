package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.common.UserContext;
import com.feilv.domain.Complaint;
import com.feilv.domain.ComplaintAction;
import com.feilv.repository.ComplaintActionRepository;
import com.feilv.repository.ComplaintRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ComplaintService {
    private final ComplaintRepository complaintRepository;
    private final ComplaintActionRepository actionRepository;
    private final AuditService auditService;

    @Transactional
    public Complaint handle(long id, String status, String result, int compensationFen) {
        Complaint complaint = complaintRepository.findById(id)
                .orElseThrow(() -> BizException.of(404, "投诉工单不存在"));
        if (!"OPEN".equals(complaint.getStatus()) && !"PROCESSING".equals(complaint.getStatus())) {
            throw BizException.of(4090, "该工单已办结");
        }
        if (!"PROCESSING".equals(status) && !"DONE".equals(status)) {
            throw BizException.of(400, "工单状态不合法");
        }
        if ("DONE".equals(status) && (result == null || result.isBlank())) {
            throw BizException.of(400, "办结结果不能为空");
        }
        complaint.setStatus(status);
        complaint.setResult(result == null ? "" : result);
        complaint.setCompensationFen(Math.max(0, compensationFen));
        complaint.setHandledBy(UserContext.userId());
        complaint.setHandledTime(java.time.LocalDateTime.now());
        complaintRepository.save(complaint);

        ComplaintAction action = new ComplaintAction();
        action.setComplaintId(id);
        action.setOperatorId(UserContext.userId());
        action.setAction(status.equals("DONE") ? "CLOSE" : "ACCEPT");
        action.setDetail(result);
        actionRepository.save(action);
        auditService.record("COMPLAINT_" + status, "complaintId=" + id + ", compensationFen=" + compensationFen);
        return complaint;
    }
}
