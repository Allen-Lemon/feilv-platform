package com.feilv.service;

import com.feilv.common.BizException;
import com.feilv.domain.BanWord;
import com.feilv.repository.BanWordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 品类管控（对应详细设计 4.2.8 / 7.6）：合规底线功能。
 * 一级禁发（处方药、烟草、易燃易爆等）直接拦截；清单后台可维护，新增即生效。
 */
@Service
@RequiredArgsConstructor
public class BanWordService {

    private static final Pattern MOBILE = Pattern.compile("^1\\d{10}$");

    private final BanWordRepository banWordRepository;

    /** 下单/商品发布前的强制拦截校验 */
    @Transactional(readOnly = true)
    public void checkGoods(String goodsDesc) {
        if (goodsDesc == null || goodsDesc.isBlank()) return;
        List<BanWord> bans = banWordRepository.findByEnabled(1);
        for (BanWord b : bans) {
            if (goodsDesc.contains(b.getWord())) {
                throw BizException.of(4300,
                        "物品【" + b.getWord() + "】属于平台禁发/限发品类，根据平台规则及法规要求无法代购代送");
            }
        }
    }

    public static void checkMobile(String mobile) {
        if (!MOBILE.matcher(mobile).matches()) {
            throw BizException.of(400, "手机号格式不正确");
        }
    }
}
