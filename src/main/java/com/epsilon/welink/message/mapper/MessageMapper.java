package com.epsilon.welink.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.epsilon.welink.message.entity.Message;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    int insertBatch(@Param("list") List<Message> messages);
}
