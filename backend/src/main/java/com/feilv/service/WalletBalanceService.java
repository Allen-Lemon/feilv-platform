package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.*;
import com.feilv.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/** Internal wallet primitives, not an HTTP adjustment/recharge API. Callers hold the order lock first if applicable. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class WalletBalanceService {
    private final UserRepository users;
    private final WalletAccountRepository accounts;
    private final WalletEntryRepository entries;
    private final EntityManager entityManager;

    public Map<Long, User> lockUsers(Long... ids) {
        Map<Long, User> result = new LinkedHashMap<>();
        // Lock ALL users before ANY wallet. This also serializes first-account creation.
        for (Long id : Arrays.stream(ids).filter(Objects::nonNull).distinct().sorted().toList()) {
            User user = users.findByIdForUpdate(id).orElseThrow(() -> BizException.of(409, "钱包所属用户不存在"));
            entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
            customer(user);
            result.put(id, user);
        }
        return result;
    }

    public Map<Long, WalletAccount> lockAccounts(Collection<Long> userIds) {
        Map<Long, WalletAccount> result = new LinkedHashMap<>();
        for (Long id : userIds.stream().distinct().sorted().toList()) {
            WalletAccount account = accounts.findByUserIdForUpdate(id).map(existing -> {
                entityManager.refresh(existing, LockModeType.PESSIMISTIC_WRITE);
                return existing;
            }).orElseGet(() -> {
                var created = new WalletAccount(); created.setUserId(id);
                created.setCreatedTime(LocalDateTime.now()); created.setUpdatedTime(created.getCreatedTime());
                return accounts.saveAndFlush(created);
            });
            result.put(id, account);
        }
        return result;
    }

    public void credit(WalletAccount account, long amount, boolean newIncome) {
        nonnegative(amount);
        long repaid = Math.min(account.getDebtFen(), amount);
        long debt = subtract(account.getDebtFen(), repaid);
        long available = add(account.getAvailableFen(), subtract(amount, repaid));
        long income = newIncome ? add(account.getTotalIncomeFen(), amount) : account.getTotalIncomeFen();
        account.setDebtFen(debt); account.setAvailableFen(available); account.setTotalIncomeFen(income);
        touch(account);
    }

    public void debit(WalletAccount account, long amount) {
        nonnegative(amount);
        long paid = Math.min(account.getAvailableFen(), amount);
        long debt = add(account.getDebtFen(), subtract(amount, paid));
        account.setAvailableFen(subtract(account.getAvailableFen(), paid)); account.setDebtFen(debt);
        // Existing frozen withdrawals are never used to pay complaint debt.
        touch(account);
    }

    public void entry(WalletAccount account, String key, String type, long amount, Long orderId,
                      Long complaintId, Long withdrawalId, String remark) {
        var entry = new WalletEntry(); entry.setUserId(account.getUserId()); entry.setBusinessKey(key); entry.setType(type);
        entry.setAmountFen(amount); entry.setAvailableAfterFen(account.getAvailableFen());
        entry.setFrozenAfterFen(account.getFrozenFen()); entry.setDebtAfterFen(account.getDebtFen());
        entry.setOrderId(orderId); entry.setComplaintId(complaintId); entry.setWithdrawalId(withdrawalId);
        entry.setRemark(remark); entry.setCreatedTime(LocalDateTime.now()); entries.saveAndFlush(entry);
    }

    public static User customer(User user) {
        if (!Set.of("USER", "RIDER").contains(user.getRole())) throw BizException.of(403, "仅用户或骑手可使用钱包");
        return user;
    }
    public static User active(User user) {
        customer(user);
        if (user.getStatus() != 1) throw BizException.of(403, "账户不可用");
        return user;
    }
    public static long add(long a, long b) {
        try { return Math.addExact(a, b); }
        catch (ArithmeticException e) { throw BizException.of(409, "钱包金额超出long范围，操作已取消"); }
    }
    public static long subtract(long a, long b) {
        try { return Math.subtractExact(a, b); }
        catch (ArithmeticException e) { throw BizException.of(409, "钱包金额超出long范围，操作已取消"); }
    }
    public static void touch(WalletAccount account) { account.setUpdatedTime(LocalDateTime.now()); }
    private static void nonnegative(long amount) {
        if (amount < 0) throw BizException.of(409, "账务金额不得为负数");
    }
}
