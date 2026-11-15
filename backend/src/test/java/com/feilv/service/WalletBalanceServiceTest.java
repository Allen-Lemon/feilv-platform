package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.WalletAccount;
import com.feilv.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Checked long arithmetic and credit/debit invariants independently of persistence. */
class WalletBalanceServiceTest {
    private final WalletBalanceService service = new WalletBalanceService(mock(UserRepository.class),
            mock(WalletAccountRepository.class), mock(WalletEntryRepository.class), mock(EntityManager.class));

    @Test void checkedAddAndSubtractRejectBothOverflowDirections() {
        assertEquals(Long.MAX_VALUE, WalletBalanceService.add(Long.MAX_VALUE - 1, 1));
        assertEquals(Long.MIN_VALUE, WalletBalanceService.subtract(Long.MIN_VALUE + 1, 1));
        assertEquals(409, assertThrows(BizException.class, () -> WalletBalanceService.add(Long.MAX_VALUE, 1)).getCode());
        assertEquals(409, assertThrows(BizException.class, () -> WalletBalanceService.add(Long.MIN_VALUE, -1)).getCode());
        assertEquals(409, assertThrows(BizException.class, () -> WalletBalanceService.subtract(Long.MIN_VALUE, 1)).getCode());
        assertEquals(409, assertThrows(BizException.class, () -> WalletBalanceService.subtract(Long.MAX_VALUE, -1)).getCode());
    }
    @Test void partialCreditRepaysDebtAndStillCountsAsIncome() {
        WalletAccount a = account(0, 700, 500, 1000);
        service.credit(a, 300, true);
        assertEquals(0, a.getAvailableFen()); assertEquals(200, a.getDebtFen());
        assertEquals(700, a.getFrozenFen()); assertEquals(1300, a.getTotalIncomeFen());
    }
    @Test void exactDebtRepaymentDoesNotLeavePositiveAvailable() {
        WalletAccount a = account(0, 700, 500, 1000);
        service.credit(a, 500, true);
        assertEquals(0, a.getDebtFen()); assertEquals(0, a.getAvailableFen()); assertEquals(1500, a.getTotalIncomeFen());
    }
    @Test void excessCreditLeavesOnlyRemainderAvailable() {
        WalletAccount a = account(0, 700, 500, 1000);
        service.credit(a, 650, true);
        assertEquals(0, a.getDebtFen()); assertEquals(150, a.getAvailableFen()); assertEquals(700, a.getFrozenFen());
    }
    @Test void unfreezeCreditRepaysDebtWithoutCountingIncomeTwice() {
        WalletAccount a = account(0, 0, 500, 1000);
        service.credit(a, 700, false);
        assertEquals(0, a.getDebtFen()); assertEquals(200, a.getAvailableFen()); assertEquals(1000, a.getTotalIncomeFen());
    }
    @Test void debitUsesAvailableThenCreatesDebtButNeverTouchesFrozen() {
        WalletAccount a = account(100, 700, 0, 1000);
        service.debit(a, 600);
        assertEquals(0, a.getAvailableFen()); assertEquals(500, a.getDebtFen());
        assertEquals(700, a.getFrozenFen()); assertEquals(1000, a.getTotalIncomeFen());
        service.debit(a, 200); assertEquals(700, a.getDebtFen());
    }
    @Test void overflowingCreditDoesNotPartiallyRepayDebtOrMutateIncome() {
        WalletAccount a = account(0, 700, 500, Long.MAX_VALUE);
        assertThrows(BizException.class, () -> service.credit(a, 300, true));
        assertEquals(500, a.getDebtFen()); assertEquals(0, a.getAvailableFen()); assertEquals(Long.MAX_VALUE, a.getTotalIncomeFen());
        WalletAccount b = account(Long.MAX_VALUE, 0, 0, 0);
        assertThrows(BizException.class, () -> service.credit(b, 1, true));
        assertEquals(Long.MAX_VALUE, b.getAvailableFen()); assertEquals(0, b.getTotalIncomeFen());
    }
    @Test void overflowingDebitAndNegativeAmountsDoNotMutateAccount() {
        WalletAccount a = account(0, 700, Long.MAX_VALUE, 1000);
        assertThrows(BizException.class, () -> service.debit(a, 1));
        assertThrows(BizException.class, () -> service.debit(a, -1));
        assertThrows(BizException.class, () -> service.credit(a, -1, true));
        assertEquals(Long.MAX_VALUE, a.getDebtFen()); assertEquals(700, a.getFrozenFen()); assertEquals(0, a.getAvailableFen());
    }
    private WalletAccount account(long available, long frozen, long debt, long income) {
        WalletAccount a = new WalletAccount(); a.setAvailableFen(available); a.setFrozenFen(frozen); a.setDebtFen(debt); a.setTotalIncomeFen(income); return a;
    }
}
