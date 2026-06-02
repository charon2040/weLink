package com.epsilon.welink.message.dto;

import lombok.Data;

@Data
public class HistoryMessageRequest {
    private String conversationType;
    private Long targetId;
    private Integer pageNum;
    private Integer pageSize;
    private Long startTime;
    private Long endTime;
}
