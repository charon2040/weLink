package com.epsilon.welink.user.controller;

import com.epsilon.welink.common.result.Result;
import com.epsilon.welink.user.dto.LoginRequest;
import com.epsilon.welink.user.dto.LoginResponse;
import com.epsilon.welink.user.dto.RegisterRequest;
import com.epsilon.welink.user.entity.User;
import com.epsilon.welink.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterRequest request) {
        userService.register(request);
        return Result.success();
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = userService.login(request);
        return Result.success(response);
    }

    @PostMapping("/refresh")
    public Result<LoginResponse> refresh(@RequestBody java.util.Map<String, String> body) {
        LoginResponse response = userService.refresh(body.get("refreshToken"));
        return Result.success(response);
    }

    @GetMapping("/user/search")
    public Result<User> searchUserByUsername(@RequestParam String username) {
        User user = userService.getUserByUsername(username);
        if (user == null) {
            return Result.error(404, "用户不存在");
        }
        return Result.success(user);
    }

    @GetMapping("/user/{userId}")
    public Result<User> getUserInfo(@PathVariable Long userId) {
        User user = userService.getUserInfo(userId);
        if (user == null) {
            return Result.error(404, "用户不存在");
        }
        return Result.success(user);
    }
}
