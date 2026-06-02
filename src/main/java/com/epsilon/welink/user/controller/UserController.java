package com.epsilon.welink.user.controller;

import com.epsilon.welink.common.result.Result;
import com.epsilon.welink.user.entity.User;
import com.epsilon.welink.user.service.UserService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/{userId}")
    public Result<User> getUserInfo(@PathVariable Long userId) {
        User user = userService.getUserInfo(userId);
        return Result.success(user);
    }
}
