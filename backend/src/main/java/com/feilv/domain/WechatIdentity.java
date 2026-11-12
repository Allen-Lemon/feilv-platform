package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** 微信身份与平台用户的映射；不保存 session_key，不把 openid 当作客户端凭证。 */
@Getter
@Setter
@Entity
@Table(name = "t_wechat_identity", uniqueConstraints = {
        @UniqueConstraint(name = "uk_wechat_app_openid", columnNames = {"app_id", "openid"})
})
public class WechatIdentity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_id", nullable = false, length = 64)
    private String appId;

    @Column(nullable = false, length = 128)
    private String openid;

    @Column(length = 128)
    private String unionid;

    @Column(name = "user_id", nullable = false)
    private Long userId;
}
