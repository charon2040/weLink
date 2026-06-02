package com.epsilon.welink.message.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("conversation")
public class Conversation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Integer bizType;      // 1=single, 2=group
    private String ownerKey;      // single:{minUid}:{maxUid} or group:{groupId}
    private String lastMsgId;
    private Long lastSeq;
    private Long lastMessageAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
