package com.epsilon.welink.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.epsilon.welink.message.entity.Message;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {
}
