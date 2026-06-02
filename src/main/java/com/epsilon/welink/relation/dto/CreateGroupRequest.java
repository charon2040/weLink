package com.epsilon.welink.relation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreateGroupRequest {
    @NotBlank(message = "群组名称不能为空")
    @Size(min = 2, max = 50, message = "群组名称长度为2-50字符")
    private String groupName;

    private String notice;

    private List<Long> memberIds;
}
