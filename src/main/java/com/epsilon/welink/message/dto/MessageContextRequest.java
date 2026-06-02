package com.epsilon.welink.message.dto;

import lombok.Data;

@Data
public class MessageContextRequest {
    private Integer conversationType;
    private Long targetId;
    private String msgId;
    private Integer beforeLimit;
    private Integer afterLimit;
}
