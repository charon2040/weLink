package com.epsilon.welink.relation.controller;

import com.epsilon.welink.common.result.Result;
import com.epsilon.welink.relation.dto.CreateGroupRequest;
import com.epsilon.welink.relation.entity.GroupInfo;
import com.epsilon.welink.relation.entity.GroupMember;
import com.epsilon.welink.relation.service.RelationService;
import com.epsilon.welink.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class RelationController {

    private final RelationService relationService;

    public RelationController(RelationService relationService) {
        this.relationService = relationService;
    }

    @PostMapping("/friend/apply/{friendId}")
    public Result<Void> sendFriendRequest(@RequestAttribute("userId") Long userId,
                                          @PathVariable Long friendId) {
        relationService.sendFriendRequest(userId, friendId);
        return Result.success();
    }

    @PostMapping("/friend/accept/{friendId}")
    public Result<Void> acceptFriendRequest(@RequestAttribute("userId") Long userId,
                                            @PathVariable Long friendId) {
        relationService.acceptFriendRequest(userId, friendId);
        return Result.success();
    }

    @PostMapping("/friend/reject/{friendId}")
    public Result<Void> rejectFriendRequest(@RequestAttribute("userId") Long userId,
                                            @PathVariable Long friendId) {
        relationService.rejectFriendRequest(userId, friendId);
        return Result.success();
    }

    @GetMapping("/friend/list")
    public Result<List<User>> getFriendList(@RequestAttribute("userId") Long userId) {
        List<User> friendList = relationService.getFriendList(userId);
        return Result.success(friendList);
    }

    @GetMapping("/friend/requests/pending")
    public Result<List<User>> getPendingFriendRequests(@RequestAttribute("userId") Long userId) {
        List<User> pendingRequests = relationService.getPendingFriendRequests(userId);
        return Result.success(pendingRequests);
    }

    @PostMapping("/friend/apply/username")
    public Result<Void> sendFriendRequestByUsername(@RequestAttribute("userId") Long userId,
                                                     @RequestParam String username) {
        relationService.sendFriendRequestByUsername(userId, username);
        return Result.success();
    }

    @DeleteMapping("/friend/{friendId}")
    public Result<Void> deleteFriend(@RequestAttribute("userId") Long userId,
                                     @PathVariable Long friendId) {
        relationService.deleteFriend(userId, friendId);
        return Result.success();
    }

    @PostMapping("/group")
    public Result<GroupInfo> createGroup(@RequestAttribute("userId") Long userId,
                                         @Valid @RequestBody CreateGroupRequest request) {
        GroupInfo groupInfo = relationService.createGroup(userId, request);
        return Result.success(groupInfo);
    }

    @PostMapping("/group/join/{groupId}")
    public Result<Void> joinGroup(@RequestAttribute("userId") Long userId,
                                  @PathVariable Long groupId) {
        relationService.joinGroup(userId, groupId);
        return Result.success();
    }

    @PostMapping("/group/join/by-name")
    public Result<Void> joinGroupByName(@RequestAttribute("userId") Long userId,
                                        @RequestParam String groupName) {
        relationService.joinGroupByName(userId, groupName);
        return Result.success();
    }

    @PostMapping("/group/join/by-no")
    public Result<Void> joinGroupByNo(@RequestAttribute("userId") Long userId,
                                      @RequestParam String groupNo) {
        relationService.joinGroupByNo(userId, groupNo);
        return Result.success();
    }

    @PostMapping("/group/{groupId}/invite/by-username")
    public Result<java.util.Map<String, Object>> inviteByUsername(@RequestAttribute("userId") Long userId,
                                                                  @PathVariable Long groupId,
                                                                  @RequestBody java.util.List<String> usernames) {
        return Result.success(relationService.inviteByUsernames(userId, groupId, usernames));
    }

    @GetMapping("/group/list")
    public Result<List<GroupInfo>> getGroupList(@RequestAttribute("userId") Long userId) {
        List<GroupInfo> groupList = relationService.getGroupList(userId);
        return Result.success(groupList);
    }

    @GetMapping("/group/{groupId}/members")
    public Result<List<GroupMember>> getGroupMembers(@PathVariable Long groupId) {
        List<GroupMember> members = relationService.getGroupMembers(groupId);
        return Result.success(members);
    }

    @GetMapping("/group/{groupId}")
    public Result<GroupInfo> getGroupInfo(@PathVariable Long groupId) {
        return Result.success(relationService.getGroupInfo(groupId));
    }

    @PostMapping("/group/{groupId}/notice")
    public Result<Void> updateNotice(@RequestAttribute("userId") Long userId,
                                     @PathVariable Long groupId,
                                     @RequestBody java.util.Map<String, String> body) {
        relationService.updateGroupNotice(userId, groupId, body.getOrDefault("notice", ""));
        return Result.success();
    }

    @PostMapping("/group/{groupId}/invite")
    public Result<Void> inviteMembers(@RequestAttribute("userId") Long userId,
                                      @PathVariable Long groupId,
                                      @RequestBody List<Long> memberIds) {
        relationService.inviteMembers(userId, groupId, memberIds);
        return Result.success();
    }

    @DeleteMapping("/group/{groupId}/kick/{targetId}")
    public Result<Void> kickMember(@RequestAttribute("userId") Long userId,
                                   @PathVariable Long groupId,
                                   @PathVariable Long targetId) {
        relationService.kickMember(userId, groupId, targetId);
        return Result.success();
    }

    @DeleteMapping("/group/{groupId}/quit")
    public Result<Void> quitGroup(@RequestAttribute("userId") Long userId,
                                  @PathVariable Long groupId) {
        relationService.quitGroup(userId, groupId);
        return Result.success();
    }

    @PostMapping("/group/{groupId}/transfer/{newOwnerId}")
    public Result<Void> transferOwnership(@RequestAttribute("userId") Long userId,
                                          @PathVariable Long groupId,
                                          @PathVariable Long newOwnerId) {
        relationService.transferOwnership(userId, groupId, newOwnerId);
        return Result.success();
    }

    @DeleteMapping("/group/{groupId}")
    public Result<Void> dissolveGroup(@RequestAttribute("userId") Long userId,
                                      @PathVariable Long groupId) {
        relationService.dissolveGroup(userId, groupId);
        return Result.success();
    }
}
