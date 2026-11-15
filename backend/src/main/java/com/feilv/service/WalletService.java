package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.common.UserContext;
import com.feilv.domain.*;
import com.feilv.dto.WalletDtos.*;
import com.feilv.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.feilv.service.WalletBalanceService.*;

@Service
@RequiredArgsConstructor
public class WalletService {
    private final UserRepository users;
    private final WalletUserRepository walletUsers;
    private final WalletAccountRepository accounts;
    private final WalletEntryRepository entries;
    private final WalletWithdrawalRepository withdrawals;
    private final WalletBalanceService balances;
    private final AdminMenuAccess access;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public WalletVO mine() {
        User user = current();
        return wallet(user, accounts.findById(user.getId()).orElse(null));
    }
    @Transactional(readOnly = true)
    public Page<EntryVO> myEntries(int page) {
        User user = current();
        return entries.findByUserId(user.getId(), page(page)).map(WalletService::entry);
    }
    @Transactional(readOnly = true)
    public Page<WithdrawalVO> myWithdrawals(int page) {
        User user = current();
        return withdrawals.findByUserId(user.getId(), page(page)).map(WalletService::withdrawal);
    }

    @Transactional
    public WithdrawalVO requestWithdrawal(WithdrawalReq request) {
        if (request == null || request.amountFen() == null || request.amountFen() < 1 || request.amountFen() > 100000000
                || request.requestId() == null || !request.requestId().matches("[A-Za-z0-9_-]{8,64}")) {
            throw BizException.of(400, "提现金额为1..100000000整数分，requestId为8..64位字母数字横线或下划线");
        }
        long userId = UserContext.userId();
        User user = active(balances.lockUsers(userId).get(userId));
        // Same-key replay returns the original result even if mobile/debt/available changed afterwards.
        WalletWithdrawal existing = withdrawals.findRequestForUpdate(userId, request.requestId()).orElse(null);
        if (existing != null) {
            if (existing.getAmountFen() != request.amountFen()) throw BizException.of(409, "同一requestId不得使用不同金额");
            return withdrawal(existing);
        }
        if (!UserViews.mobileBound(user)) throw BizException.of(403, "请先绑定手机号再申请MOCK提现");
        WalletAccount account = balances.lockAccounts(List.of(userId)).get(userId);
        if (account.getDebtFen() != 0) throw BizException.of(409, "存在欠款，不可申请新提现");
        if (request.amountFen() > account.getAvailableFen()) throw BizException.of(409, "可用余额不足");
        long available = subtract(account.getAvailableFen(), request.amountFen());
        long frozen = add(account.getFrozenFen(), request.amountFen());
        account.setAvailableFen(available); account.setFrozenFen(frozen); touch(account);
        var record = new WalletWithdrawal(); record.setUserId(userId); record.setAmountFen(request.amountFen());
        record.setRequestId(request.requestId()); record.setCreatedTime(LocalDateTime.now()); withdrawals.saveAndFlush(record);
        balances.entry(account, "WITHDRAW_FREEZE:" + record.getId(), "WITHDRAWAL_FREEZE", subtract(0, request.amountFen()),
                null, null, record.getId(), "MOCK提现申请冻结可用余额，无真实资金划转");
        return withdrawal(record);
    }

    @Transactional(readOnly = true)
    public Page<AdminWalletVO> adminWallets(int page, Long userId) {
        access.requireMenu("wallets"); validateId(userId);
        Page<User> found = walletUsers.search(userId, page(page));
        Map<Long, WalletAccount> byUser = accounts.findAllById(found.map(User::getId).getContent()).stream()
                .collect(Collectors.toMap(WalletAccount::getUserId, Function.identity()));
        return found.map(u -> {
            WalletVO w = wallet(u, byUser.get(u.getId()));
            return new AdminWalletVO(w.userId(), w.availableFen(), w.frozenFen(), w.debtFen(), w.totalIncomeFen(),
                    w.totalWithdrawnFen(), w.channel(), w.withdrawAllowed(), mask(u.getNickname()), UserViews.maskedMobile(u), u.getRole(), u.getStatus());
        });
    }
    @Transactional(readOnly = true)
    public Page<EntryVO> adminEntries(long userId, int page) {
        access.requireMenu("wallets"); validateId(userId);
        customer(users.findById(userId).orElseThrow(() -> BizException.of(404, "用户不存在")));
        return entries.findByUserId(userId, page(page)).map(WalletService::entry);
    }
    @Transactional(readOnly = true)
    public Page<WithdrawalVO> adminWithdrawals(int page, String status, Long userId) {
        access.requireMenu("withdrawals"); validateId(userId);
        if (status != null && status.isBlank()) status = null;
        if (status != null && !Set.of("PENDING", "APPROVED", "REJECTED").contains(status)) throw BizException.of(400, "提现status不合法");
        return withdrawals.search(userId, status, page(page)).map(WalletService::withdrawal);
    }

    @Transactional
    public WithdrawalVO reviewWithdrawal(long id, WithdrawalReviewReq request) {
        access.requireMenu("withdrawals"); validateId(id);
        if (request == null || !Set.of("APPROVED", "REJECTED").contains(request.decision() == null ? "" : request.decision())
                || request.reason() == null || request.reason().isBlank() || request.reason().length() > 255) {
            throw BizException.of(400, "审核决定仅APPROVED/REJECTED，原因为1..255字符");
        }
        long userId = withdrawals.findUserId(id).orElseThrow(() -> BizException.of(404, "提现申请不存在"));
        var locked = balances.lockUsers(userId);
        // Approval/rejection of an existing freeze is allowed even if user is now frozen or has debt.
        WalletAccount account = balances.lockAccounts(locked.keySet()).get(userId);
        WalletWithdrawal record = withdrawals.findByIdForUpdate(id).orElseThrow(() -> BizException.of(404, "提现申请不存在"));
        entityManager.refresh(record, LockModeType.PESSIMISTIC_WRITE);
        if (!"PENDING".equals(record.getStatus())) throw BizException.of(409, "该提现已审核，不可重复审核");
        if (account.getFrozenFen() < record.getAmountFen()) throw BizException.of(409, "提现冻结金额异常，操作已取消");
        long frozen = subtract(account.getFrozenFen(), record.getAmountFen());
        if ("APPROVED".equals(request.decision())) {
            long total = add(account.getTotalWithdrawnFen(), record.getAmountFen());
            account.setFrozenFen(frozen); account.setTotalWithdrawnFen(total); touch(account);
            balances.entry(account, "WITHDRAW_REVIEW:" + id, "WITHDRAWAL_APPROVED", subtract(0, record.getAmountFen()),
                    null, null, id, "MOCK提现批准出账，无真实资金划转");
        } else {
            account.setFrozenFen(frozen);
            balances.credit(account, record.getAmountFen(), false);
            balances.entry(account, "WITHDRAW_REVIEW:" + id, "WITHDRAWAL_REJECTED", record.getAmountFen(),
                    null, null, id, "MOCK提现驳回解冻，先抵欠款再回可用余额");
        }
        record.setStatus(request.decision()); record.setReason(mask(request.reason().trim()));
        record.setReviewedBy(UserContext.userId()); record.setReviewedTime(LocalDateTime.now());
        withdrawals.saveAndFlush(record);
        return withdrawal(record);
    }

    private User current() {
        return active(users.findById(UserContext.userId()).orElseThrow(() -> BizException.of(403, "账户不存在")));
    }
    private static WalletVO wallet(User user, WalletAccount account) {
        long available = account == null ? 0 : account.getAvailableFen();
        long frozen = account == null ? 0 : account.getFrozenFen();
        long debt = account == null ? 0 : account.getDebtFen();
        boolean allowed = user.getStatus() == 1 && Set.of("USER", "RIDER").contains(user.getRole())
                && UserViews.mobileBound(user) && debt == 0 && available > 0;
        return new WalletVO(user.getId(), available, frozen, debt, account == null ? 0 : account.getTotalIncomeFen(),
                account == null ? 0 : account.getTotalWithdrawnFen(), "MOCK", allowed);
    }
    private static EntryVO entry(WalletEntry e) {
        return new EntryVO(e.getId(), e.getType(), e.getAmountFen(), e.getAvailableAfterFen(), e.getFrozenAfterFen(),
                e.getDebtAfterFen(), e.getOrderId(), e.getComplaintId(), e.getWithdrawalId(), mask(e.getRemark()), e.getCreatedTime());
    }
    private static WithdrawalVO withdrawal(WalletWithdrawal w) {
        return new WithdrawalVO(w.getId(), w.getUserId(), w.getAmountFen(), w.getStatus(), w.getRequestId(), mask(w.getReason()),
                w.getReviewedBy(), w.getReviewedTime(), w.getCreatedTime(), "MOCK");
    }
    private static Pageable page(int page) {
        if (page < 0) throw BizException.of(400, "page必须为非负整数");
        return PageRequest.of(page, 15, Sort.by(Sort.Direction.DESC, "id"));
    }
    private static void validateId(Long id) { if (id != null && id <= 0) throw BizException.of(400, "ID必须为正整数"); }
    private static String mask(String text) {
        return text == null ? null : text.replaceAll("(?<!\\d)(1[3-9]\\d)(\\d{4})(\\d{4})(?!\\d)", "$1****$3");
    }
}
