package com.epsilon.welink.message.dto;

import lombok.Data;

@Data
public class MessageRequest {
    private String msgId;
    private Long toUserId;
    private Long groupId;
    private Integer msgType;
    private String content;
}
