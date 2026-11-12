package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** 禁发/限发品类（t_ban_word）— 合规底线，管理后台可维护（详细设计4.2.8） */
@Getter @Setter
@Entity @Table(name = "t_ban_word")
public class BanWord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String word;

    @Column(nullable = false)
    private int level = 1;   // 1禁发(拦截) 2限发(提示)

    @Column(nullable = false)
    private int enabled = 1;
}
