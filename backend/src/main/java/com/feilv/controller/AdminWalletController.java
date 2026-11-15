package com.feilv.controller;

import com.feilv.common.Result;
import com.feilv.dto.WalletDtos.*;
import com.feilv.service.AdminMenuAccess;
import com.feilv.service.AdminQueryParameters;
import com.feilv.service.WalletService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminWalletController {
    private final WalletService wallets;
    private final AdminMenuAccess access;
    @ModelAttribute
    public void noStore(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    @GetMapping("/wallets")
    public Result<Page<AdminWalletVO>> list(@RequestParam(defaultValue = "0") String page,
                                           @RequestParam(required = false) String userId) {
        access.requireMenu("wallets");
        return Result.ok(wallets.adminWallets(AdminQueryParameters.page(page), optionalId(userId)));
    }
    @GetMapping("/wallets/{userId}/entries")
    public Result<Page<EntryVO>> entries(@PathVariable String userId, @RequestParam(defaultValue = "0") String page) {
        access.requireMenu("wallets");
        return Result.ok(wallets.adminEntries(AdminQueryParameters.id(userId), AdminQueryParameters.page(page)));
    }
    @GetMapping("/withdrawals")
    public Result<Page<WithdrawalVO>> withdrawals(@RequestParam(defaultValue = "0") String page,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String userId) {
        access.requireMenu("withdrawals");
        return Result.ok(wallets.adminWithdrawals(AdminQueryParameters.page(page), status, optionalId(userId)));
    }
    @PostMapping("/withdrawals/{id}/review")
    public Result<WithdrawalVO> review(@PathVariable String id, @Valid @RequestBody WithdrawalReviewReq request) {
        access.requireMenu("withdrawals");
        return Result.ok(wallets.reviewWithdrawal(AdminQueryParameters.id(id), request));
    }
    private static Long optionalId(String id) { return id == null ? null : AdminQueryParameters.id(id); }
}
