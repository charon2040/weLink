package com.epsilon.welink.common.exception;

import com.epsilon.welink.common.result.ResultCode;
import lombok.Getter;

/* 业务异常类，用于处理业务逻辑中的异常情况.
包含状态码和消息字段，方便前端处理.
提供构造方法，用于创建异常实例. */
@Getter
public class BusinessException extends RuntimeException {
    private final Integer code;

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
