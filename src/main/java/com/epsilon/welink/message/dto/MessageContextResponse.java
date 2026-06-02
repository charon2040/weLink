package com.epsilon.welink.message.dto;

import com.epsilon.welink.message.entity.Message;
import lombok.Data;

import java.util.List;

@Data
public class MessageContextResponse {
    private String anchorMsgId;
    private List<Message> records;
}
