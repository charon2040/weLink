package com.epsilon.welink.im.event;

import lombok.Data;

@Data
public class GroupMessageIngressEvent {

    private String traceId;
    private String msgId;
    private String clientMsgId;
    private Long fromUserId;
    private Long groupId;
    private Integer msgType;
    private String content;
    private long receiveTime;
    private String sourceInstanceId;
}
