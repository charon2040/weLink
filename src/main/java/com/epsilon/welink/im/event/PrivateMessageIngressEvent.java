package com.epsilon.welink.im.event;

import lombok.Data;

@Data
public class PrivateMessageIngressEvent {

    private String traceId;
    private String msgId;
    private String clientMsgId;
    private Long fromUserId;
    private Long toUserId;
    private Integer msgType;
    private String content;
    private long receiveTime;
    private String sourceInstanceId;
}
