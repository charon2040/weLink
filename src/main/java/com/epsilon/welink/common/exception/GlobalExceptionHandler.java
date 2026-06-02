package com.epsilon.welink.common.exception;

import com.epsilon.welink.common.result.Result;
import com.epsilon.welink.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/* 全局异常处理类，用于处理所有异常情况.
包含业务异常和通用异常的处理方法.
提供日志记录和结果封装，方便前端处理. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusinessException(BusinessException e) {
        log.error("Business exception: code={}, message={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        log.error("Unexpected exception", e);
        return Result.error(ResultCode.INTERNAL_ERROR, e.getMessage() != null ? e.getMessage() : "服务器内部错误");
    }
}
