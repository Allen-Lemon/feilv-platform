package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Check;

import java.time.LocalDateTime;

/** MOCK only. Always lock t_user before creating/locking its account. */
@Getter
@Setter
@Entity
@Table(name = "t_wallet_account")
@Check(constraints = "available_fen >= 0 and frozen_fen >= 0 and debt_fen >= 0 and total_income_fen >= 0 and total_withdrawn_fen >= 0 and (available_fen = 0 or debt_fen = 0)")
public class WalletAccount {
    @Id
    @Column(name = "user_id")
    private Long userId;
    @Column(name = "available_fen", nullable = false)
    private long availableFen;
    @Column(name = "frozen_fen", nullable = false)
    private long frozenFen;
    @Column(name = "debt_fen", nullable = false)
    private long debtFen;
    /** Cumulative new credits, including the part used to repay debt; excludes unfreezes. */
    @Column(name = "total_income_fen", nullable = false)
    private long totalIncomeFen;
    @Column(name = "total_withdrawn_fen", nullable = false)
    private long totalWithdrawnFen;
    @Column(name = "created_time", nullable = false, updatable = false)
    private LocalDateTime createdTime;
    @Column(name = "updated_time", nullable = false)
    private LocalDateTime updatedTime;
}
