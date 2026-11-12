package com.feilv.common;

import lombok.Getter;

/** 业务异常：携带错误码，由全局异常处理器转换为统一响应 */
@Getter
public class BizException extends RuntimeException {
    private final int code;

    public BizException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    public static BizException of(int code, String msg) { return new BizException(code, msg); }
}
