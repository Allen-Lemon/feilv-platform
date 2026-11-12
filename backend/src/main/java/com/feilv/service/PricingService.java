package com.feilv.service;

import com.feilv.dto.ApiDtos.FeeItem;
import com.feilv.dto.ApiDtos.QuoteVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 计价引擎（对应功能设计文档 3.3 / 详细设计表3-2）：
 * 配送费＝起步价＋里程＋重量＋时段动态加成，规则全部后台可配置（feilv.pricing.*）。
 * 预估与结算偏差≤10%，超出取小值向用户收取（OrderService 结算时校验）。
 */
@Service
public class PricingService {

    @Value("${feilv.pricing.base-fee-fen}")
    private int baseFeeFen;

    @Value("${feilv.pricing.base-distance-m}")
    private int baseDistanceM;

    @Value("${feilv.pricing.per-km-fen}")
    private int perKmFen;

    @Value("${feilv.pricing.night-rate}")
    private int nightRate;   // 夜间加成百分比，120 表示 +20%

    private static final int[] WEIGHT_THRESHOLD = {5, 10, 20};
    private static final int[] WEIGHT_ADD_FEN = {0, 200, 400, 800}; // 0-5:0, 5-10:2元, 10-20:4元, >20:8元

    public QuoteVO quote(int distanceM, int weightKg) {
        List<FeeItem> items = new ArrayList<>();
        int total = baseFeeFen;
        items.add(new FeeItem("BASE", "起步价（含2公里）", baseFeeFen));

        if (distanceM > baseDistanceM) {
            int km = (int) Math.ceil((distanceM - baseDistanceM) / 1000.0);
            int fee = km * perKmFen;
            total += fee;
            items.add(new FeeItem("DISTANCE", "里程费（超出" + km + "公里）", fee));
        }

        int weightFee = weightFee(weightKg);
        if (weightFee > 0) {
            total += weightFee;
            items.add(new FeeItem("WEIGHT", "重量附加（" + weightKg + "公斤）", weightFee));
        }

        if (isNight(LocalTime.now())) {
            int nightFee = total * (nightRate - 100) / 100;
            total += nightFee;
            items.add(new FeeItem("NIGHT", "夜间服务费", nightFee));
        }
        return new QuoteVO(total, items);
    }

    private int weightFee(int weightKg) {
        if (weightKg <= WEIGHT_THRESHOLD[0]) return WEIGHT_ADD_FEN[0];
        if (weightKg <= WEIGHT_THRESHOLD[1]) return WEIGHT_ADD_FEN[1];
        if (weightKg <= WEIGHT_THRESHOLD[2]) return WEIGHT_ADD_FEN[2];
        return WEIGHT_ADD_FEN[3];
    }

    /** 夜间时段：22:00—次日7:00 */
    private boolean isNight(LocalTime t) {
        return t.getHour() >= 22 || t.getHour() < 7;
    }
}
