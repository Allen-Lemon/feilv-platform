package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 用户/骑手/管理员主档（t_user） */
@Getter @Setter
@Entity @Table(name = "t_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 微信首次登录可暂未绑定手机号；MySQL 唯一索引允许多个 NULL。 */
    @Column(unique = true, length = 20)
    private String mobile;

    private String nickname;

    /** 服务端校验、重新编码后保存的头像路径，不接受客户端临时文件路径。 */
    @Column(name = "avatar_url", length = 255)
    private String avatarUrl;

    @Column(nullable = false)
    private String role = "USER"; // USER / RIDER / ADMIN

    @Column(nullable = false)
    private int status = 1;       // 1正常 0冻结

    @Column(name = "created_time", insertable = false, updatable = false)
    private LocalDateTime createdTime;
}
