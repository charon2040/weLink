package com.epsilon.welink.message.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ConversationSummaryDTO {
    private Integer conversationType;
    private Long targetId;
    private String lastMessage;
    private LocalDateTime lastTime;
    private Integer unreadCount;
}
