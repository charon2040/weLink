package com.epsilon.welink.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.epsilon.welink.message.entity.OutboxPending;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OutboxPendingMapper extends BaseMapper<OutboxPending> {

    int insertBatch(@Param("list") List<OutboxPending> pendingList);
}
