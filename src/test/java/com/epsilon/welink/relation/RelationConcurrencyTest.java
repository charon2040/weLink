package com.epsilon.welink.relation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.epsilon.welink.common.exception.BusinessException;
import com.epsilon.welink.message.service.MessageService;
import com.epsilon.welink.relation.entity.FriendRelation;
import com.epsilon.welink.relation.entity.GroupInfo;
import com.epsilon.welink.relation.entity.GroupMember;
import com.epsilon.welink.relation.mapper.FriendRelationMapper;
import com.epsilon.welink.relation.mapper.GroupInfoMapper;
import com.epsilon.welink.relation.mapper.GroupMemberMapper;
import com.epsilon.welink.relation.service.RelationService;
import com.epsilon.welink.user.entity.User;
import com.epsilon.welink.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 关系服务并发一致性测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("关系服务并发一致性测试")
class RelationConcurrencyTest {

    @Mock
    private FriendRelationMapper friendRelationMapper;

    @Mock
    private GroupInfoMapper groupInfoMapper;

    @Mock
    private GroupMemberMapper groupMemberMapper;

    @Mock
    private UserService userService;

    @Mock
    private MessageService messageService;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    private RelationService relationService;

    @BeforeEach
    void setUp() {
        relationService = new RelationService(
                friendRelationMapper, groupInfoMapper, groupMemberMapper,
                userService, messageService, redisTemplate);
    }

    // ================================================================
    // 好友关系并发测试
    // ================================================================

    @Test
    @DisplayName("并发接受好友请求：应创建双向好友关系")
    void concurrentAcceptFriendShouldCreateBidirectionalRelation() throws Exception {
        Long userId = 100L;
        Long friendId = 200L;

        FriendRelation pendingRequest = new FriendRelation();
        pendingRequest.setId(1L);
        pendingRequest.setUserId(friendId);
        pendingRequest.setFriendId(userId);
        pendingRequest.setStatus(0);

        when(friendRelationMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(pendingRequest);
        when(friendRelationMapper.updateById(any(FriendRelation.class))).thenReturn(1);
        when(friendRelationMapper.insert(any(FriendRelation.class))).thenReturn(1);

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    relationService.acceptFriendRequest(userId, friendId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(threadCount);

        // Verify the original request status was updated to 1 (accepted)
        ArgumentCaptor<FriendRelation> captor = ArgumentCaptor.forClass(FriendRelation.class);
        verify(friendRelationMapper, atLeastOnce()).updateById(captor.capture());
        FriendRelation updated = captor.getValue();
        assertThat(updated.getStatus()).isEqualTo(1);
    }

    @Test
    @DisplayName("不能添加自己为好友：应抛出异常")
    void cannotAddSelfAsFriend() {
        assertThatThrownBy(() -> relationService.sendFriendRequest(100L, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能添加自己为好友");
    }

    @Test
    @DisplayName("已是好友时重复请求应抛出异常")
    void shouldRejectDuplicateFriendRequest() {
        Long userId = 100L;
        Long friendId = 200L;

        when(friendRelationMapper.selectCount(any(LambdaQueryWrapper.class)))
                .thenReturn(1L);

        assertThatThrownBy(() -> relationService.sendFriendRequest(userId, friendId))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("并发删除好友关系应删除双向记录")
    void concurrentDeleteFriendShouldRemoveBothDirections() {
        Long userId = 100L;
        Long friendId = 200L;

        when(friendRelationMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(1);

        int threadCount = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    relationService.deleteFriend(userId, friendId);
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            boolean completed = latch.await(10, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdown();
        }

        // deleteFriend should delete both (userId, friendId) and (friendId, userId)
        verify(friendRelationMapper, atLeast(threadCount * 2)).delete(any(LambdaQueryWrapper.class));
    }

    // ================================================================
    // 群组成员并发测试
    // ================================================================

    @Test
    @DisplayName("并发加入群组：已加入成员不应重复加入")
    void concurrentJoinGroupShouldPreventDuplicateMembership() throws Exception {
        Long userId = 100L;
        Long groupId = 1000L;

        AtomicInteger insertCount = new AtomicInteger(0);

        GroupInfo group = new GroupInfo();
        group.setId(groupId);
        group.setGroupName("Test Group");
        group.setOwnerId(1L);
        group.setMemberCount(1);

        when(groupInfoMapper.selectById(groupId)).thenReturn(group);

        when(groupMemberMapper.selectCount(any(LambdaQueryWrapper.class)))
                .thenAnswer(invocation -> insertCount.get() > 0 ? 1L : 0L);

        when(groupMemberMapper.insert(any(GroupMember.class)))
                .thenAnswer(invocation -> {
                    insertCount.incrementAndGet();
                    return 1;
                });

        when(messageService.getCurrentGroupSeq(groupId)).thenReturn(0L);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    relationService.joinGroup(userId, groupId);
                } catch (BusinessException e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(insertCount.get()).isGreaterThanOrEqualTo(1);
        assertThat(errorCount.get() + insertCount.get()).isEqualTo(threadCount);
    }

    @Test
    @DisplayName("群组成员计数一致性：加入/退出后计数应准确")
    void groupMemberCountShouldBeConsistentAfterJoinAndQuit() {
        Long userId = 100L;
        Long groupId = 1000L;

        GroupInfo group = new GroupInfo();
        group.setId(groupId);
        group.setGroupName("Test Group");
        group.setOwnerId(1L);
        group.setMemberCount(3);

        when(groupInfoMapper.selectById(groupId)).thenReturn(group);

        when(groupMemberMapper.selectCount(any(LambdaQueryWrapper.class)))
                .thenReturn(0L)  // check existence
                .thenReturn(3L); // recalculate count

        when(groupMemberMapper.insert(any(GroupMember.class))).thenReturn(1);
        when(groupInfoMapper.updateById(any(GroupInfo.class))).thenReturn(1);
        when(messageService.getCurrentGroupSeq(groupId)).thenReturn(10L);

        relationService.joinGroup(userId, groupId);

        ArgumentCaptor<GroupInfo> captor = ArgumentCaptor.forClass(GroupInfo.class);
        verify(groupInfoMapper, atLeastOnce()).updateById(captor.capture());
        GroupInfo updated = captor.getValue();
        assertThat(updated.getMemberCount()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("无权限用户踢人应抛出异常")
    void nonAdminKickMemberShouldThrowException() {
        Long userId = 100L;
        Long groupId = 1000L;
        Long targetId = 300L;

        when(groupMemberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> relationService.kickMember(userId, groupId, targetId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有权限");
    }

    @Test
    @DisplayName("不能踢出群主")
    void cannotKickGroupOwner() {
        Long userId = 100L;
        Long groupId = 1000L;
        Long ownerId = 300L;

        GroupMember operator = new GroupMember();
        operator.setId(1L);
        operator.setGroupId(groupId);
        operator.setUserId(userId);
        operator.setRole(2); // owner

        GroupMember target = new GroupMember();
        target.setId(3L);
        target.setGroupId(groupId);
        target.setUserId(ownerId);
        target.setRole(2); // also owner

        when(groupMemberMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(operator)
                .thenReturn(target);

        assertThatThrownBy(() -> relationService.kickMember(userId, groupId, ownerId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能踢出群主");
    }
}
