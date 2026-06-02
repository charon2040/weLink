package com.epsilon.welink.message.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.epsilon.welink.common.result.Result;
import com.epsilon.welink.message.dto.ConversationSummaryDTO;
import com.epsilon.welink.message.dto.MessageContextRequest;
import com.epsilon.welink.message.dto.MessageContextResponse;
import com.epsilon.welink.message.dto.MessageSearchRequest;
import com.epsilon.welink.message.entity.Message;
import com.epsilon.welink.message.service.MessageService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

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

    @PostMapping("/search")
    public Result<Page<Message>> searchMessages(@RequestAttribute("userId") Long userId,
                                                @RequestBody MessageSearchRequest request) {
        return Result.success(messageService.searchMessages(userId, request));
    }

    @PostMapping("/context")
    public Result<MessageContextResponse> getMessageContext(@RequestAttribute("userId") Long userId,
                                                            @RequestBody MessageContextRequest request) {
        return Result.success(messageService.getMessageContext(userId, request));
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

    @PostMapping("/sync")
    public Result<Map<String, Object>> syncMessages(@RequestAttribute("userId") Long userId,
                                                     @RequestBody Map<String, Long> cursors) {
        List<Message> messages = new ArrayList<>();
        Map<String, Long> newCursors = new java.util.LinkedHashMap<>();
        if (cursors != null) {
            for (Map.Entry<String, Long> entry : cursors.entrySet()) {
                String convKey = entry.getKey();
                Long cursor = entry.getValue() != null ? entry.getValue() : 0L;
                List<Message> batch = messageService.getMessagesByConversationAndCursor(convKey, cursor, 200);
                messages.addAll(batch);
                if (!batch.isEmpty()) {
                    Long maxSeq = batch.stream()
                            .map(m -> m.getConversationSeq() != null ? m.getConversationSeq() : m.getGroupSeq())
                            .filter(s -> s != null)
                            .max(Long::compareTo)
                            .orElse(cursor);
                    newCursors.put(convKey, maxSeq);
                } else {
                    newCursors.put(convKey, cursor);
                }
            }
        }
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("messages", messages);
        result.put("cursors", newCursors);
        return Result.success(result);
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
