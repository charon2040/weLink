package com.epsilon.welink.relation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.epsilon.welink.common.constant.RedisConstants;
import com.epsilon.welink.common.exception.BusinessException;
import com.epsilon.welink.common.result.ResultCode;
import com.epsilon.welink.message.service.MessageService;
import com.epsilon.welink.relation.dto.CreateGroupRequest;
import com.epsilon.welink.relation.entity.FriendRelation;
import com.epsilon.welink.relation.entity.GroupInfo;
import com.epsilon.welink.relation.entity.GroupMember;
import com.epsilon.welink.relation.mapper.FriendRelationMapper;
import com.epsilon.welink.relation.mapper.GroupInfoMapper;
import com.epsilon.welink.relation.mapper.GroupMemberMapper;
import com.epsilon.welink.user.entity.User;
import com.epsilon.welink.user.service.UserService;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class RelationService {

    private final FriendRelationMapper friendRelationMapper;
    private final GroupInfoMapper groupInfoMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final UserService userService;
    private final MessageService messageService;
    private final RedisTemplate<String, Object> redisTemplate;

    public RelationService(FriendRelationMapper friendRelationMapper,
                           GroupInfoMapper groupInfoMapper,
                           GroupMemberMapper groupMemberMapper,
                           UserService userService,
                           MessageService messageService,
                           RedisTemplate<String, Object> redisTemplate) {
        this.friendRelationMapper = friendRelationMapper;
        this.groupInfoMapper = groupInfoMapper;
        this.groupMemberMapper = groupMemberMapper;
        this.userService = userService;
        this.messageService = messageService;
        this.redisTemplate = redisTemplate;
    }

    public void sendFriendRequest(Long userId, Long friendId) {
        if (userId.equals(friendId)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "不能添加自己为好友");
        }

        // 只看 PENDING / ACCEPTED 状态; REJECTED 不阻塞重新申请
        LambdaQueryWrapper<FriendRelation> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(FriendRelation::getUserId, userId)
                .eq(FriendRelation::getFriendId, friendId)
                .in(FriendRelation::getStatus, 0, 1);
        if (friendRelationMapper.selectCount(queryWrapper) > 0) {
            throw new BusinessException(ResultCode.FRIEND_ALREADY_EXISTS);
        }

        // 之前被拒过(status=2) 直接复用旧行翻为 PENDING, 避免 uk_user_friend 唯一约束撞
        LambdaQueryWrapper<FriendRelation> rejectedQuery = new LambdaQueryWrapper<>();
        rejectedQuery.eq(FriendRelation::getUserId, userId)
                .eq(FriendRelation::getFriendId, friendId)
                .eq(FriendRelation::getStatus, 2);
        FriendRelation rejected = friendRelationMapper.selectOne(rejectedQuery);
        if (rejected != null) {
            rejected.setStatus(0);
            friendRelationMapper.updateById(rejected);
            return;
        }

        FriendRelation relation = new FriendRelation();
        relation.setUserId(userId);
        relation.setFriendId(friendId);
        relation.setStatus(0);
        friendRelationMapper.insert(relation);
    }

    public void acceptFriendRequest(Long userId, Long friendId) {
        LambdaQueryWrapper<FriendRelation> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(FriendRelation::getUserId, friendId)
                .eq(FriendRelation::getFriendId, userId)
                .eq(FriendRelation::getStatus, 0);
        FriendRelation relation = friendRelationMapper.selectOne(queryWrapper);

        if (relation == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "好友申请不存在");
        }

        relation.setStatus(1);
        friendRelationMapper.updateById(relation);

        FriendRelation reverseRelation = new FriendRelation();
        reverseRelation.setUserId(userId);
        reverseRelation.setFriendId(friendId);
        reverseRelation.setStatus(1);
        friendRelationMapper.insert(reverseRelation);
        invalidateFriendIdsCache(userId, friendId);
    }

    public void rejectFriendRequest(Long userId, Long friendId) {
        LambdaQueryWrapper<FriendRelation> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(FriendRelation::getUserId, friendId)
                .eq(FriendRelation::getFriendId, userId)
                .eq(FriendRelation::getStatus, 0);
        FriendRelation relation = friendRelationMapper.selectOne(queryWrapper);

        if (relation == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "好友申请不存在");
        }

        relation.setStatus(2);
        friendRelationMapper.updateById(relation);
    }

    public List<User> getFriendList(Long userId) {
        LambdaQueryWrapper<FriendRelation> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(FriendRelation::getUserId, userId)
                .eq(FriendRelation::getStatus, 1);
        List<FriendRelation> relations = friendRelationMapper.selectList(queryWrapper);

        List<Long> friendIds = relations.stream().map(FriendRelation::getFriendId).toList();
        Map<Long, User> userMap = userService.getUserInfoMap(friendIds);

        List<String> onlineKeys = friendIds.stream()
                .map(id -> RedisConstants.USER_ONLINE_PREFIX + id)
                .toList();
        List<Object> onlineStatuses = redisTemplate.opsForValue().multiGet(onlineKeys);

        java.util.Map<Long, Boolean> onlineMap = new java.util.HashMap<>();
        if (onlineStatuses != null) {
            for (int i = 0; i < friendIds.size() && i < onlineStatuses.size(); i++) {
                onlineMap.put(friendIds.get(i), onlineStatuses.get(i) != null);
            }
        }

        return friendIds.stream()
                .map(id -> {
                    User friend = userMap.get(id);
                    if (friend != null) {
                        friend.setOnline(onlineMap.getOrDefault(id, false));
                    }
                    return friend;
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public List<Long> getFriendIds(Long userId) {
        String cacheKey = RedisConstants.FRIEND_IDS_PREFIX + userId;
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<Long> result = (List<Long>) list;
            return result;
        }
        LambdaQueryWrapper<FriendRelation> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(FriendRelation::getUserId, userId)
                .eq(FriendRelation::getStatus, 1);
        List<Long> friendIds = friendRelationMapper.selectList(queryWrapper).stream()
                .map(FriendRelation::getFriendId)
                .toList();
        try {
            redisTemplate.opsForValue().set(
                    cacheKey,
                    friendIds,
                    RedisConstants.FRIEND_IDS_CACHE_TTL_MINUTES,
                    TimeUnit.MINUTES
            );
        } catch (Exception ignored) {
        }
        return friendIds;
    }

    public List<User> getPendingFriendRequests(Long userId) {
        LambdaQueryWrapper<FriendRelation> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(FriendRelation::getFriendId, userId)
                .eq(FriendRelation::getStatus, 0);
        List<FriendRelation> relations = friendRelationMapper.selectList(queryWrapper);

        List<Long> requestorIds = relations.stream().map(FriendRelation::getUserId).toList();
        Map<Long, User> userMap = userService.getUserInfoMap(requestorIds);

        return requestorIds.stream()
                .map(userMap::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public void sendFriendRequestByUsername(Long userId, String username) {
        User targetUser = userService.getUserByUsername(username);
        if (targetUser == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND, "用户不存在");
        }
        sendFriendRequest(userId, targetUser.getId());
    }

    public void deleteFriend(Long userId, Long friendId) {
        LambdaQueryWrapper<FriendRelation> queryWrapper1 = new LambdaQueryWrapper<>();
        queryWrapper1.eq(FriendRelation::getUserId, userId)
                .eq(FriendRelation::getFriendId, friendId);
        friendRelationMapper.delete(queryWrapper1);

        LambdaQueryWrapper<FriendRelation> queryWrapper2 = new LambdaQueryWrapper<>();
        queryWrapper2.eq(FriendRelation::getUserId, friendId)
                .eq(FriendRelation::getFriendId, userId);
        friendRelationMapper.delete(queryWrapper2);
        invalidateFriendIdsCache(userId, friendId);
    }

    @Transactional
    public GroupInfo createGroup(Long userId, CreateGroupRequest request) {
        GroupInfo groupInfo = new GroupInfo();
        groupInfo.setGroupName(request.getGroupName());
        groupInfo.setOwnerId(userId);
        groupInfo.setMemberCount(1);
        groupInfo.setStatus(1);
        groupInfo.setNotice(request.getNotice());
        groupInfo.setGroupNo(generateUniqueGroupNo());
        groupInfoMapper.insert(groupInfo);

        GroupMember owner = new GroupMember();
        owner.setGroupId(groupInfo.getId());
        owner.setUserId(userId);
        owner.setRole(2);
        owner.setLastReadSeq(0L);
        groupMemberMapper.insert(owner);
        invalidateGroupMemberCache(groupInfo.getId());

        if (request.getMemberIds() != null && !request.getMemberIds().isEmpty()) {
            for (Long memberId : request.getMemberIds()) {
                if (!memberId.equals(userId)) {
                    GroupMember member = new GroupMember();
                    member.setGroupId(groupInfo.getId());
                    member.setUserId(memberId);
                    member.setRole(0);
                    member.setLastReadSeq(0L);
                    groupMemberMapper.insert(member);
                }
            }
            groupInfo.setMemberCount(groupInfo.getMemberCount() + request.getMemberIds().size());
            groupInfoMapper.updateById(groupInfo);
        }

        return groupInfo;
    }

    public List<GroupInfo> getGroupList(Long userId) {
        LambdaQueryWrapper<GroupMember> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(GroupMember::getUserId, userId);
        List<GroupMember> memberships = groupMemberMapper.selectList(queryWrapper);

        return memberships.stream()
                .map(m -> groupInfoMapper.selectById(m.getGroupId()))
                .toList();
    }

    public void joinGroup(Long userId, Long groupId) {
        GroupInfo groupInfo = groupInfoMapper.selectById(groupId);
        if (groupInfo == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "群组不存在");
        }

        LambdaQueryWrapper<GroupMember> existQuery = new LambdaQueryWrapper<>();
        existQuery.eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getUserId, userId);
        if (groupMemberMapper.selectCount(existQuery) > 0) {
            throw new BusinessException(ResultCode.GROUP_NO_PERMISSION, "已经是群成员");
        }

        GroupMember newMember = new GroupMember();
        newMember.setGroupId(groupId);
        newMember.setUserId(userId);
        newMember.setRole(0);
        newMember.setLastReadSeq(messageService.getCurrentGroupSeq(groupId));
        groupMemberMapper.insert(newMember);
        invalidateGroupMemberCache(groupId);

        groupInfo.setMemberCount(groupMemberMapper.selectCount(
                new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getGroupId, groupId)
        ).intValue());
        groupInfoMapper.updateById(groupInfo);
    }

    public void joinGroupByName(Long userId, String groupName) {
        LambdaQueryWrapper<GroupInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(GroupInfo::getGroupName, groupName);
        GroupInfo groupInfo = groupInfoMapper.selectOne(queryWrapper);

        if (groupInfo == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "群组不存在");
        }

        joinGroup(userId, groupInfo.getId());
    }

    public void joinGroupByNo(Long userId, String groupNo) {
        GroupInfo groupInfo = groupInfoMapper.selectOne(new LambdaQueryWrapper<GroupInfo>()
                .eq(GroupInfo::getGroupNo, groupNo));
        if (groupInfo == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "群号不存在");
        }
        joinGroup(userId, groupInfo.getId());
    }

    /** 生成 8 位唯一群号. 雪花 id 取模 + 防重试. */
    private String generateUniqueGroupNo() {
        java.util.Random rnd = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 5; i++) {
            int n = 10_000_000 + rnd.nextInt(90_000_000);  // 8 位
            String candidate = String.valueOf(n);
            Long count = groupInfoMapper.selectCount(new LambdaQueryWrapper<GroupInfo>()
                    .eq(GroupInfo::getGroupNo, candidate));
            if (count == null || count == 0) return candidate;
        }
        // 兜底: 用 currentTimeMillis 后 8 位
        return String.valueOf(System.currentTimeMillis()).substring(5);
    }

    /** 按用户名邀请: 把 usernames 转成 userIds 复用 inviteMembers. 用户名不存在的会被跳过. */
    @Transactional
    public java.util.Map<String, Object> inviteByUsernames(Long userId, Long groupId, List<String> usernames) {
        List<Long> resolvedIds = new java.util.ArrayList<>();
        List<String> notFound = new java.util.ArrayList<>();
        for (String name : usernames) {
            if (name == null || name.isBlank()) continue;
            User u = userService.getUserByUsername(name.trim());
            if (u == null) {
                notFound.add(name);
            } else {
                resolvedIds.add(u.getId());
            }
        }
        if (!resolvedIds.isEmpty()) {
            inviteMembers(userId, groupId, resolvedIds);
        }
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("invited", resolvedIds.size());
        result.put("notFound", notFound);
        return result;
    }

    private static final String GROUP_MEMBER_CACHE_PREFIX = "group:members:";
    private static final String GROUP_MEMBER_LITE_CACHE_PREFIX = "group:members:lite:";
    private static final long GROUP_MEMBER_CACHE_TTL_MINUTES = 5;

    /** 群主修改群公告. 普通成员调用抛权限错. */
    @Transactional
    public void updateGroupNotice(Long userId, Long groupId, String notice) {
        GroupInfo info = groupInfoMapper.selectById(groupId);
        if (info == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "群组不存在");
        }
        if (!userId.equals(info.getOwnerId())) {
            throw new BusinessException(ResultCode.GROUP_NO_PERMISSION, "只有群主可以修改群公告");
        }
        info.setNotice(notice);
        groupInfoMapper.updateById(info);
    }

    public GroupInfo getGroupInfo(Long groupId) {
        return groupInfoMapper.selectById(groupId);
    }

    public List<GroupMember> getGroupMembers(Long groupId) {
        String cacheKey = GROUP_MEMBER_CACHE_PREFIX + groupId;
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached instanceof List<?> list && !list.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<GroupMember> result = (List<GroupMember>) list;
            return result;
        }

        List<GroupMember> members = loadGroupMembers(groupId);
        Map<Long, User> userMap = Collections.emptyMap();
        try {
            userMap = userService.getUserInfoMap(members.stream().map(GroupMember::getUserId).toList());
        } catch (Exception ignored) {
        }
        for (GroupMember m : members) {
            User u = userMap.get(m.getUserId());
            if (u != null) {
                m.setUsername(u.getUsername());
                m.setNickname(u.getNickname());
                m.setAvatar(u.getAvatar());
            }
        }

        try {
            redisTemplate.opsForValue().set(cacheKey, members, GROUP_MEMBER_CACHE_TTL_MINUTES, java.util.concurrent.TimeUnit.MINUTES);
        } catch (Exception ignored) {
        }

        return members;
    }

    public List<GroupMember> getGroupMembersForDispatch(Long groupId) {
        String cacheKey = GROUP_MEMBER_LITE_CACHE_PREFIX + groupId;
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached instanceof List<?> list && !list.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<GroupMember> result = (List<GroupMember>) list;
            return result;
        }

        List<GroupMember> members = loadGroupMembers(groupId);
        try {
            redisTemplate.opsForValue().set(cacheKey, members, GROUP_MEMBER_CACHE_TTL_MINUTES, java.util.concurrent.TimeUnit.MINUTES);
        } catch (Exception ignored) {
        }
        return members;
    }

    private List<GroupMember> loadGroupMembers(Long groupId) {
        LambdaQueryWrapper<GroupMember> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(GroupMember::getGroupId, groupId);
        return groupMemberMapper.selectList(queryWrapper);
    }

    private void invalidateGroupMemberCache(Long groupId) {
        try {
            redisTemplate.delete(GROUP_MEMBER_CACHE_PREFIX + groupId);
            redisTemplate.delete(GROUP_MEMBER_LITE_CACHE_PREFIX + groupId);
        } catch (Exception ignored) {
        }
    }

    private void invalidateFriendIdsCache(Long... userIds) {
        try {
            for (Long userId : userIds) {
                if (userId != null) {
                    redisTemplate.delete(RedisConstants.FRIEND_IDS_PREFIX + userId);
                }
            }
        } catch (Exception ignored) {
        }
    }

    public void inviteMembers(Long userId, Long groupId, List<Long> memberIds) {
        GroupMember member = groupMemberMapper.selectOne(
                new LambdaQueryWrapper<GroupMember>()
                        .eq(GroupMember::getGroupId, groupId)
                        .eq(GroupMember::getUserId, userId)
                        .in(GroupMember::getRole, 1, 2)
        );

        if (member == null) {
            throw new BusinessException(ResultCode.GROUP_NO_PERMISSION);
        }

        for (Long memberId : memberIds) {
            LambdaQueryWrapper<GroupMember> existQuery = new LambdaQueryWrapper<>();
            existQuery.eq(GroupMember::getGroupId, groupId)
                    .eq(GroupMember::getUserId, memberId);
            if (groupMemberMapper.selectCount(existQuery) == 0) {
                GroupMember newMember = new GroupMember();
                newMember.setGroupId(groupId);
                newMember.setUserId(memberId);
                newMember.setRole(0);
                newMember.setLastReadSeq(messageService.getCurrentGroupSeq(groupId));
                groupMemberMapper.insert(newMember);
            }
            invalidateGroupMemberCache(groupId);
        }

        GroupInfo groupInfo = groupInfoMapper.selectById(groupId);
        groupInfo.setMemberCount(groupMemberMapper.selectCount(
                new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getGroupId, groupId)
        ).intValue());
        groupInfoMapper.updateById(groupInfo);
    }

    public void kickMember(Long userId, Long groupId, Long targetId) {
        GroupMember operator = groupMemberMapper.selectOne(
                new LambdaQueryWrapper<GroupMember>()
                        .eq(GroupMember::getGroupId, groupId)
                        .eq(GroupMember::getUserId, userId)
                        .in(GroupMember::getRole, 1, 2)
        );

        if (operator == null) {
            throw new BusinessException(ResultCode.GROUP_NO_PERMISSION);
        }

        GroupMember target = groupMemberMapper.selectOne(
                new LambdaQueryWrapper<GroupMember>()
                        .eq(GroupMember::getGroupId, groupId)
                        .eq(GroupMember::getUserId, targetId)
        );

        if (target == null) {
            throw new BusinessException(ResultCode.GROUP_NOT_MEMBER);
        }

        if (target.getRole() == 2) {
            throw new BusinessException(ResultCode.GROUP_NO_PERMISSION, "不能踢出群主");
        }

        groupMemberMapper.deleteById(target.getId());
        invalidateGroupMemberCache(groupId);

        GroupInfo groupInfo = groupInfoMapper.selectById(groupId);
        groupInfo.setMemberCount(groupMemberMapper.selectCount(
                new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getGroupId, groupId)
        ).intValue());
        groupInfoMapper.updateById(groupInfo);
    }

    public void quitGroup(Long userId, Long groupId) {
        GroupMember member = groupMemberMapper.selectOne(
                new LambdaQueryWrapper<GroupMember>()
                        .eq(GroupMember::getGroupId, groupId)
                        .eq(GroupMember::getUserId, userId)
        );

        if (member == null) {
            throw new BusinessException(ResultCode.GROUP_NOT_MEMBER);
        }

        if (member.getRole() == 2) {
            throw new BusinessException(ResultCode.GROUP_NO_PERMISSION,
                    "群主不能直接退群，请先转让群主 (/group/{id}/transfer/{newOwnerId}) 或解散群 (DELETE /group/{id})");
        }

        groupMemberMapper.deleteById(member.getId());
        invalidateGroupMemberCache(groupId);

        GroupInfo groupInfo = groupInfoMapper.selectById(groupId);
        groupInfo.setMemberCount(groupMemberMapper.selectCount(
                new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getGroupId, groupId)
        ).intValue());
        groupInfoMapper.updateById(groupInfo);
    }

    /**
     * 群主把群主权限转让给另一个群成员. 转让后原群主降为普通成员.
     */
    @Transactional
    public void transferOwnership(Long currentOwnerId, Long groupId, Long newOwnerId) {
        if (currentOwnerId.equals(newOwnerId)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "新群主不能是当前群主");
        }
        GroupMember owner = groupMemberMapper.selectOne(
                new LambdaQueryWrapper<GroupMember>()
                        .eq(GroupMember::getGroupId, groupId)
                        .eq(GroupMember::getUserId, currentOwnerId)
                        .eq(GroupMember::getRole, 2)
        );
        if (owner == null) {
            throw new BusinessException(ResultCode.GROUP_NO_PERMISSION, "只有群主可以转让群主权限");
        }
        GroupMember newOwner = groupMemberMapper.selectOne(
                new LambdaQueryWrapper<GroupMember>()
                        .eq(GroupMember::getGroupId, groupId)
                        .eq(GroupMember::getUserId, newOwnerId)
        );
        if (newOwner == null) {
            throw new BusinessException(ResultCode.GROUP_NOT_MEMBER, "目标用户不是群成员");
        }

        owner.setRole(0);
        newOwner.setRole(2);
        groupMemberMapper.updateById(owner);
        groupMemberMapper.updateById(newOwner);

        GroupInfo info = groupInfoMapper.selectById(groupId);
        info.setOwnerId(newOwnerId);
        groupInfoMapper.updateById(info);

        invalidateGroupMemberCache(groupId);
    }

    /**
     * 群主解散群: 标记 group_info.status=0, 清空 group_member, 失效缓存.
     * 历史消息保留在 message 表中, 不做删除.
     */
    @Transactional
    public void dissolveGroup(Long userId, Long groupId) {
        GroupMember member = groupMemberMapper.selectOne(
                new LambdaQueryWrapper<GroupMember>()
                        .eq(GroupMember::getGroupId, groupId)
                        .eq(GroupMember::getUserId, userId)
                        .eq(GroupMember::getRole, 2)
        );
        if (member == null) {
            throw new BusinessException(ResultCode.GROUP_NO_PERMISSION, "只有群主可以解散群");
        }

        groupMemberMapper.delete(new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getGroupId, groupId));

        GroupInfo info = groupInfoMapper.selectById(groupId);
        if (info != null) {
            info.setStatus(0);
            info.setMemberCount(0);
            groupInfoMapper.updateById(info);
        }

        invalidateGroupMemberCache(groupId);
    }
}
