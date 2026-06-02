package com.epsilon.welink.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("file_meta")
public class FileMeta {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String fileId;
    private String objectName;
    private String originalFilename;
    private Long size;
    private String mimeType;
    private Long uploaderId;
    private LocalDateTime createdAt;
}
