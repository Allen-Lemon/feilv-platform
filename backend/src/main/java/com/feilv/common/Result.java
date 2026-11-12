package com.feilv.common;

import lombok.Getter;

/** 统一响应体（功能设计文档10.2：内部接口统一REST风格） */
@Getter
public class Result<T> {
    private final int code;       // 0成功；非0为错误码
    private final String msg;
    private final T data;

    private Result(int code, String msg, T data) {
        this.code = code; this.msg = msg; this.data = data;
    }

    public static <T> Result<T> ok(T data) { return new Result<>(0, "success", data); }
    public static Result<Void> ok() { return ok(null); }
    public static <T> Result<T> fail(int code, String msg) { return new Result<>(code, msg, null); }
}
