package com.epsilon.welink.message.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("read_cursor")
public class ReadCursor {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String conversationKey;
    private Long readSeq;
    private Long updatedAt;
}
