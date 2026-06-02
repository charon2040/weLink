package com.epsilon.welink.common.result;

import lombok.Getter;

@Getter
public enum ResultCode {
    SUCCESS(200, "成功"),
    PARAM_ERROR(400, "参数错误"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "禁止访问"),
    NOT_FOUND(404, "资源不存在"),
    INTERNAL_ERROR(500, "服务器内部错误"),
    
    USER_NOT_FOUND(1001, "用户不存在"),
    USER_ALREADY_EXISTS(1002, "用户已存在"),
    PASSWORD_ERROR(1003, "密码错误"),
    TOKEN_EXPIRED(1004, "Token已过期"),
    TOKEN_INVALID(1005, "Token无效"),
    
    FRIEND_ALREADY_EXISTS(2001, "已是好友"),
    FRIEND_REQUEST_SENT(2002, "已发送好友申请"),
    NOT_FRIENDS(2003, "不是好友"),
    
    GROUP_NOT_FOUND(3001, "群组不存在"),
    GROUP_NOT_MEMBER(3002, "不是群成员"),
    GROUP_NO_PERMISSION(3003, "没有权限"),
    
    MESSAGE_NOT_FOUND(4001, "消息不存在"),
    MESSAGE_SEND_FAILED(4002, "消息发送失败");

    private final Integer code;
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
