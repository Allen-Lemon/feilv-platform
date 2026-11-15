package com.feilv.controller;

import com.feilv.common.Result;
import com.feilv.dto.WalletDtos.*;
import com.feilv.service.AdminQueryParameters;
import com.feilv.service.WalletService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/wallet")
@RequiredArgsConstructor
public class WalletController {
    private final WalletService wallets;
    @ModelAttribute
    public void noStore(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    @GetMapping
    public Result<WalletVO> mine() { return Result.ok(wallets.mine()); }
    @GetMapping("/entries")
    public Result<Page<EntryVO>> entries(@RequestParam(defaultValue = "0") String page) {
        return Result.ok(wallets.myEntries(AdminQueryParameters.page(page)));
    }
    @GetMapping("/withdrawals")
    public Result<Page<WithdrawalVO>> withdrawals(@RequestParam(defaultValue = "0") String page) {
        return Result.ok(wallets.myWithdrawals(AdminQueryParameters.page(page)));
    }
    @PostMapping("/withdrawals")
    public Result<WithdrawalVO> request(@Valid @RequestBody WithdrawalReq request) {
        return Result.ok(wallets.requestWithdrawal(request));
    }
}
