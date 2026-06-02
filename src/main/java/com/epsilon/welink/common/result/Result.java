package com.epsilon.welink.common.result;

import lombok.Data;

/* 通用结果类，用于封装 API 调用结果.
包含状态码、消息和数据字段，方便前端处理.
提供静态方法，用于创建成功和失败的结果实例.
使用时，直接调用静态方法即可，无需手动创建实例.
例如：
// 成功返回
return Result.success();
// 成功返回数据
return Result.success(data);
// 失败返回
return Result.error(400, "bad request");
// 失败返回自定义状态码
return Result.error(ResultCode.BAD_REQUEST, "bad request"); */
@Data
public class Result<T> {
    private Integer code;
    private String message;
    private T data;

    public static <T> Result<T> success() {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMessage("success");
        return result;
    }

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMessage("success");
        result.setData(data);
        return result;
    }

    public static <T> Result<T> error(Integer code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }

    public static <T> Result<T> error(ResultCode resultCode, String message) {
        Result<T> result = new Result<>();
        result.setCode(resultCode.getCode());
        result.setMessage(message);
        return result;
    }
}
