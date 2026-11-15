package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.*;
import com.feilv.enums.OrderStatus;
import com.feilv.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

import static com.feilv.service.WalletBalanceService.*;

/** All operations are MOCK. No payment updates, gateways, history scans, or automatic backfills. */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletAccountingService {
    private final ComplaintOrderLockRepository orders;
    private final PayRecordRepository payments;
    private final ComplaintDeductionRepository deductions;
    private final RiderRepository riders;
    private final WalletOrderSettlementRepository settlements;
    private final WalletComplaintReceiptRepository receipts;
    private final WalletBalanceService balances;
    private final EntityManager entityManager;

    /** Called only on a new FINISHED transition; missing legacy prerequisites deliberately do not block it. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void settleFinishedOrder(long orderId) {
        OrderMain order = orders.findByIdForUpdate(orderId).orElseThrow(() -> BizException.of(404, "订单不存在"));
        entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE);
        if (settlements.findByOrderIdForUpdate(orderId).isPresent()) return;
        if (order.getStatus() != OrderStatus.FINISHED) {
            log.warn("MOCK wallet settlement skipped: orderId={}, reason=NOT_FINISHED", orderId); return;
        }
        Rider rider = order.getRiderId() == null ? null : riders.findById(order.getRiderId()).orElse(null);
        if (rider == null || rider.getUserId() == null) {
            log.warn("MOCK wallet settlement skipped: orderId={}, reason=NO_RIDER", orderId); return;
        }
        List<PayRecord> successful = payments.findSuccessfulMockForUpdate(orderId);
        if (successful.isEmpty()) {
            log.warn("MOCK wallet settlement skipped: orderId={}, reason=NO_SUCCESS_MOCK_PAYMENT", orderId); return;
        }
        long gross = share(successful);
        long deducted = deducted(deductions.findByOrderIdForUpdate(orderId));
        if (deducted > gross) throw BizException.of(409, "本单累计投诉扣款超过MOCK骑手分账");
        long net = subtract(gross, deducted);
        var lockedUsers = balances.lockUsers(rider.getUserId());
        WalletAccount account = balances.lockAccounts(lockedUsers.keySet()).get(rider.getUserId());
        var settlement = new WalletOrderSettlement(); settlement.setOrderId(orderId); settlement.setRiderId(rider.getId());
        settlement.setUserId(rider.getUserId()); settlement.setGrossFen(gross); settlement.setDeductedFen(deducted);
        settlement.setNetFen(net); settlement.setCreatedTime(LocalDateTime.now()); settlements.saveAndFlush(settlement);
        balances.credit(account, net, true);
        balances.entry(account, "ORDER_SETTLE:" + orderId, "ORDER_SETTLEMENT", net, orderId, null, null,
                "MOCK订单完成净额入账（已减本单既有投诉扣款），无真实资金划转");
        // Legacy deductions are netted only; never compensate old complaints or create receipts here.
    }

    /** Caller must saveAndFlush the deduction in the SAME transaction before calling this method. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyComplaintDeduction(ComplaintDeduction entry) {
        if (entry == null || entry.getId() == null || entry.getOrderId() == null || entry.getComplaintId() == null) {
            throw BizException.of(409, "必须先在当前事务保存投诉扣款凭据");
        }
        OrderMain order = orders.findByIdForUpdate(entry.getOrderId()).orElseThrow(() -> BizException.of(404, "订单不存在"));
        entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE);
        // Current reads after the order lock avoid stale REPEATABLE_READ snapshots and double payment.
        WalletComplaintReceipt prior = receipts.findByComplaintIdForUpdate(entry.getComplaintId()).orElse(null);
        if (prior != null) {
            if (!Objects.equals(prior.getDeductionId(), entry.getId()) || prior.getAmountFen() != entry.getAmountFen()
                    || !Objects.equals(prior.getOrderId(), entry.getOrderId())
                    || !Objects.equals(prior.getUserId(), entry.getUserId()) || !Objects.equals(prior.getRiderId(), entry.getRiderId())) {
                throw BizException.of(409, "投诉钱包凭据与已有记账不一致");
            }
            return;
        }
        if (order.getStatus() != OrderStatus.DELIVERED && order.getStatus() != OrderStatus.FINISHED) {
            throw BizException.of(409, "仅已送达或完成订单可记投诉钱包账");
        }
        if (!"MOCK".equals(entry.getChannel()) || entry.getAmountFen() <= 0 || entry.getCreatedBy() == null
                || !Objects.equals(order.getUserId(), entry.getUserId()) || !Objects.equals(order.getRiderId(), entry.getRiderId())) {
            throw BizException.of(409, "投诉扣款凭据与MOCK订单不一致");
        }
        Rider rider = riders.findById(entry.getRiderId()).orElseThrow(() -> BizException.of(409, "投诉骑手不存在"));
        List<PayRecord> successful = payments.findSuccessfulMockForUpdate(order.getId());
        List<ComplaintDeduction> all = deductions.findByOrderIdForUpdate(order.getId());
        ComplaintDeduction saved = all.stream().filter(d -> Objects.equals(d.getId(), entry.getId())).findFirst()
                .orElseThrow(() -> BizException.of(409, "投诉扣款凭据尚未保存"));
        if (!Objects.equals(saved.getComplaintId(), entry.getComplaintId()) || saved.getAmountFen() != entry.getAmountFen()
                || !Objects.equals(saved.getUserId(), entry.getUserId()) || !Objects.equals(saved.getRiderId(), entry.getRiderId())
                || !Objects.equals(saved.getCreatedBy(), entry.getCreatedBy()) || !"MOCK".equals(saved.getChannel())) {
            throw BizException.of(409, "投诉扣款凭据不匹配");
        }
        WalletOrderSettlement settlement = settlements.findByOrderIdForUpdate(order.getId()).orElse(null);
        if (settlement == null && order.getStatus() == OrderStatus.FINISHED) {
            throw BizException.of(409, "历史已完成订单无钱包结算凭据，请人工核对，不自动入账");
        }
        long gross = settlement == null ? share(successful) : settlement.getGrossFen();
        if ((settlement == null && successful.isEmpty()) || deducted(all) > gross) {
            throw BizException.of(409, "本单累计投诉扣款超过MOCK骑手分账");
        }
        if (settlement != null && (!Objects.equals(settlement.getRiderId(), rider.getId())
                || !Objects.equals(settlement.getUserId(), rider.getUserId()))) {
            throw BizException.of(409, "订单结算骑手与投诉骑手不一致");
        }
        var lockedUsers = balances.lockUsers(entry.getUserId(), rider.getUserId());
        var accounts = balances.lockAccounts(lockedUsers.keySet());
        boolean debited = settlement != null;
        if (debited) {
            WalletAccount riderAccount = accounts.get(rider.getUserId());
            balances.debit(riderAccount, entry.getAmountFen());
            balances.entry(riderAccount, "COMPLAINT_DEBIT:" + entry.getComplaintId(), "COMPLAINT_DEDUCTION",
                    subtract(0, entry.getAmountFen()), order.getId(), entry.getComplaintId(), null,
                    "MOCK投诉扣款；可用不足记欠款，不动已有提现冻结");
        }
        WalletAccount publisher = accounts.get(entry.getUserId());
        balances.credit(publisher, entry.getAmountFen(), true);
        balances.entry(publisher, "COMPLAINT_CREDIT:" + entry.getComplaintId(), "COMPLAINT_COMPENSATION",
                entry.getAmountFen(), order.getId(), entry.getComplaintId(), null,
                "MOCK投诉等额赔付入账，收入优先抵欠款，无真实资金划转");
        var receipt = new WalletComplaintReceipt(); receipt.setComplaintId(entry.getComplaintId()); receipt.setDeductionId(entry.getId());
        receipt.setOrderId(order.getId()); receipt.setRiderId(rider.getId()); receipt.setRiderUserId(rider.getUserId());
        receipt.setUserId(entry.getUserId()); receipt.setAmountFen(entry.getAmountFen()); receipt.setRiderDebited(debited);
        receipt.setCreatedBy(entry.getCreatedBy()); receipt.setCreatedTime(LocalDateTime.now()); receipts.saveAndFlush(receipt);
    }

    private static long share(List<PayRecord> payments) {
        long total = 0;
        for (PayRecord payment : payments) {
            if (payment.getShareRider() < 0) throw BizException.of(409, "MOCK支付骑手分账不得为负");
            total = add(total, payment.getShareRider());
        }
        return total;
    }
    private static long deducted(List<ComplaintDeduction> deductions) {
        long total = 0;
        for (ComplaintDeduction entry : deductions) {
            if (!"MOCK".equals(entry.getChannel())) continue;
            if (entry.getAmountFen() < 0) throw BizException.of(409, "既有投诉扣款不得为负");
            total = add(total, entry.getAmountFen());
        }
        return total;
    }
}
