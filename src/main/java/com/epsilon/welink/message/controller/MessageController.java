package com.epsilon.welink.message.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.epsilon.welink.common.result.Result;
import com.epsilon.welink.message.dto.ConversationSummaryDTO;
import com.epsilon.welink.message.entity.Message;
import com.epsilon.welink.message.service.MessageService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/message")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @GetMapping("/history/private")
    public Result<Page<Message>> getPrivateHistory(
            @RequestParam Long userId,
            @RequestParam Long targetId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "50") Integer pageSize) {
        Page<Message> page = messageService.getPrivateHistory(userId, targetId, pageNum, pageSize);
        return Result.success(page);
    }

    @GetMapping("/history/group")
    public Result<Page<Message>> getGroupHistory(
            @RequestParam Long groupId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "50") Integer pageSize) {
        Page<Message> page = messageService.getGroupHistory(groupId, pageNum, pageSize);
        return Result.success(page);
    }

    @GetMapping("/conversations")
    public Result<List<ConversationSummaryDTO>> getConversationSummaries(@RequestAttribute("userId") Long userId) {
        return Result.success(messageService.getConversationSummaries(userId));
    }

    @GetMapping("/offline")
    public Result<List<Message>> getOfflineMessages(@RequestAttribute("userId") Long userId) {
        List<Message> messages = messageService.getOfflineMessages(userId);
        return Result.success(messages);
    }

    @PostMapping("/read/conversation")
    public Result<Void> markConversationAsRead(@RequestAttribute("userId") Long userId,
                                               @RequestParam Integer conversationType,
                                               @RequestParam Long targetId) {
        messageService.markConversationAsRead(userId, conversationType, targetId);
        return Result.success();
    }

    @PostMapping("/read/{msgId}")
    public Result<Void> markAsRead(@PathVariable String msgId,
                                   @RequestAttribute("userId") Long userId) {
        messageService.markAsRead(msgId, userId);
        return Result.success();
    }
}
