package com.epsilon.welink.message.dto;

import lombok.Data;

@Data
public class MessageSearchRequest {
    private Integer conversationType;
    private Long targetId;
    private String keyword;
    private Integer msgType;
    private Long startTime;
    private Long endTime;
    private Integer pageNum;
    private Integer pageSize;
}
