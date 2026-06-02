package com.epsilon.welink.im.dto;

import com.epsilon.welink.message.entity.Message;
import lombok.Data;

@Data
public class ImRetryMessage {
    private Message message;
    private Long targetUserId;
    private Integer retryCount;
}
