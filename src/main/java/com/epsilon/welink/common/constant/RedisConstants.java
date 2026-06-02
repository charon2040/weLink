package com.epsilon.welink.common.constant;

// Redis 常量类，定义 Redis 相关的常量，如键前缀、过期时间等.
public class RedisConstants {

    public static final String USER_ONLINE_PREFIX = "user:online:";
    public static final String IM_ROUTE_PREFIX = "im:route:";
    public static final String IM_ROUTE_USER_PREFIX = "im:route:user:";
    public static final String USER_INFO_PREFIX = "user:info:";
    public static final String TOKEN_BLACKLIST_PREFIX = "token:blacklist:";
    public static final String REFRESH_TOKEN_PREFIX = "token:refresh:";
    public static final String FRIEND_APPLY_LOCK_PREFIX = "friend:apply:";
    public static final String GROUP_MEMBER_LOCK_PREFIX = "group:member:";
    public static final String IM_RATE_LIMIT_PREFIX = "im:rate:";
    public static final String IM_RECENT_PRIVATE_PREFIX = "im:recent:private:";
    public static final String IM_RECENT_GROUP_PREFIX = "im:recent:group:";
    public static final String IM_MESSAGE_DETAIL_PREFIX = "im:message:detail:";
    public static final String IM_GROUP_SEQ_PREFIX = "im:group:seq:";
    public static final String IM_DEDUP_CLIENT_MSG_PREFIX = "im:dedup:client_msg:";
    public static final String IM_CONV_SEQ_PREFIX = "im:conv:seq:";
    public static final String IM_CONVERSATION_ID_PREFIX = "im:conv:id:";
    public static final String FRIEND_IDS_PREFIX = "friend:ids:";

    // Outbox 双写漏写兜底:
    // OUTBOX_RECONCILE_SET 是 SET, 存 outboxId 字符串
    // OUTBOX_RECONCILE_PREFIX + outboxId 是 HASH, 存 reconcile 需要的全部字段
    public static final String OUTBOX_RECONCILE_SET = "outbox:reconcile:set";
    public static final String OUTBOX_RECONCILE_PREFIX = "outbox:reconcile:";
    public static final long OUTBOX_RECONCILE_TTL_DAYS = 7;
    
    public static final long ONLINE_TTL_SECONDS = 300;
    public static final long ROUTE_TTL_SECONDS = 300;
    public static final long HEARTBEAT_TIMEOUT_SECONDS = 300;
    public static final long RECENT_MESSAGE_CACHE_DAYS = 7;
    public static final long RECENT_MESSAGE_CACHE_TTL_DAYS = 8;
    public static final long FRIEND_IDS_CACHE_TTL_MINUTES = 10;
    public static final long CONVERSATION_ID_CACHE_TTL_DAYS = 7;
}
