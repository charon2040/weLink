package com.epsilon.welink.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.epsilon.welink.message.entity.MessageInbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MessageInboxMapper extends BaseMapper<MessageInbox> {

    int insertBatch(@Param("list") List<MessageInbox> inboxList);
}
