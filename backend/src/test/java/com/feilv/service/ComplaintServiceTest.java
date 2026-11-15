package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.dto.ApiDtos.ComplaintReq;
import com.feilv.dto.ComplaintDtos.ComplaintReviewReq;
import com.feilv.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComplaintServiceTest {
    ComplaintRepository complaints;
    ComplaintService service;

    @BeforeEach
    void setup() {
        complaints = mock(ComplaintRepository.class);
        service = new ComplaintService(complaints, mock(ComplaintActionRepository.class),
                mock(ComplaintDeductionRepository.class), mock(ComplaintOrderLockRepository.class),
                mock(PayRecordRepository.class), mock(RiderRepository.class), mock(AuditService.class), mock(WalletAccountingService.class));
    }

    @Test
    void invalidReviewsNeverReadOrWriteDatabase() {
        for (ComplaintReviewReq req : new ComplaintReviewReq[]{null,
                new ComplaintReviewReq(null, "理由", 0), new ComplaintReviewReq("DONE", "理由", 0),
                new ComplaintReviewReq("APPROVED", null, 1), new ComplaintReviewReq("APPROVED", " ", 1),
                new ComplaintReviewReq("APPROVED", "a".repeat(256), 1), new ComplaintReviewReq("APPROVED", "理由", null),
                new ComplaintReviewReq("APPROVED", "理由", -1), new ComplaintReviewReq("REJECTED", "理由", 1),
                new ComplaintReviewReq("PROCESSING", "理由", 1)}) {
            assertEquals(400, assertThrows(BizException.class, () -> service.review(1, req)).getCode());
        }
        verifyNoInteractions(complaints);
    }

    @Test
    void legacyHandleNeverClampsOrCreatesMoneyAndRequiresReason() {
        for (int amount : new int[]{-1, 1, Integer.MAX_VALUE}) {
            assertEquals(400, assertThrows(BizException.class, () -> service.handle(1, "DONE", "办结", amount)).getCode());
        }
        assertThrows(BizException.class, () -> service.handle(1, "APPROVED", "结果", 0));
        assertThrows(BizException.class, () -> service.handle(1, "DONE", " ", 0));
        assertThrows(BizException.class, () -> service.handle(1, "PROCESSING", null, 0));
        verifyNoInteractions(complaints);
    }

    @Test
    void descriptionTypeAndOrderIdAreValidatedInServiceToo() {
        for (String description : new String[]{null, "", " ", "x".repeat(256)}) {
            assertEquals(400, assertThrows(BizException.class,
                    () -> service.create(1, new ComplaintReq(1L, "OTHER", description))).getCode());
        }
        assertThrows(BizException.class, () -> service.create(1, new ComplaintReq(1L, "BOGUS", "问题")));
        assertThrows(BizException.class, () -> service.create(1, new ComplaintReq(1L, null, "问题")));
        assertThrows(BizException.class, () -> service.create(1, new ComplaintReq(0L, "OTHER", "问题")));
        verifyNoInteractions(complaints);
    }
}
