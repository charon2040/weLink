package com.epsilon.welink.gateway;

import com.epsilon.welink.im.service.IMService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/internal")
public class InternalBusinessController {

    private final IMService imService;

    public InternalBusinessController(IMService imService) {
        this.imService = imService;
    }

    @PostMapping("/message/send")
    public String handleSendMessage(@RequestBody String messageJson) {
        return imService.handleInternalMessage(messageJson);
    }

    @PostMapping("/push/{userId}")
    public Map<String, Object> pushToUser(@PathVariable Long userId, @RequestBody String messageJson) {
        imService.pushToUserInternal(userId, messageJson);
        return Collections.singletonMap("status", "ok");
    }
}
