package com.epsilon.welink.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.epsilon.welink.common.constant.RedisConstants;
import com.epsilon.welink.common.exception.BusinessException;
import com.epsilon.welink.common.result.ResultCode;
import com.epsilon.welink.common.util.JwtUtil;
import com.epsilon.welink.user.dto.LoginRequest;
import com.epsilon.welink.user.dto.LoginResponse;
import com.epsilon.welink.user.dto.RegisterRequest;
import com.epsilon.welink.user.entity.User;
import com.epsilon.welink.user.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UserService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final RedisTemplate<String, Object> redisTemplate;
    private final BCryptPasswordEncoder passwordEncoder;

    public UserService(UserMapper userMapper, JwtUtil jwtUtil, RedisTemplate<String, Object> redisTemplate) {
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
        this.redisTemplate = redisTemplate;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    public void register(RegisterRequest request) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUsername, request.getUsername());
        if (userMapper.selectCount(queryWrapper) > 0) {
            throw new BusinessException(ResultCode.USER_ALREADY_EXISTS, "用户名已存在");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname() != null ? request.getNickname() : request.getUsername());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setStatus(1);

        userMapper.insert(user);
    }

    public LoginResponse login(LoginRequest request) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUsername, request.getUsername());
        User user = userMapper.selectOne(queryWrapper);

        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.PASSWORD_ERROR);
        }

        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername());
        String refreshToken = jwtUtil.generateRefreshToken(user.getId(), user.getUsername());

        cacheUserInfoSafely(user);
        cacheRefreshTokenSafely(user.getId(), refreshToken);

        LoginResponse response = new LoginResponse();
        response.setAccessToken(accessToken);
        response.setRefreshToken(refreshToken);

        LoginResponse.UserInfo userInfo = new LoginResponse.UserInfo();
        userInfo.setId(user.getId());
        userInfo.setUsername(user.getUsername());
        userInfo.setNickname(user.getNickname());
        userInfo.setAvatar(user.getAvatar());
        response.setUserInfo(userInfo);

        return response;
    }

    public User getUserInfo(Long userId) {
        try {
            Object cached = redisTemplate.opsForValue().get(RedisConstants.USER_INFO_PREFIX + userId);
            if (cached != null) {
                return (User) cached;
            }
        } catch (DataAccessException e) {
            log.warn("Redis unavailable when reading user info for userId={}", userId, e);
        }

        User user = userMapper.selectById(userId);
        if (user != null) {
            cacheUserInfoSafely(user);
        }
        return user;
    }

    public User getUserByUsername(String username) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUsername, username);
        return userMapper.selectOne(queryWrapper);
    }

    public Map<Long, User> getUserInfoMap(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<User> users = userMapper.selectBatchIds(userIds);
        if (users == null || users.isEmpty()) {
            return Collections.emptyMap();
        }
        users.forEach(this::cacheUserInfoSafely);
        return users.stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }

    /**
     * 用 refresh token 换一对新的 (access + refresh) token.
     * 校验顺序: JWT 签名 → type=refresh → 与 Redis 缓存的最新 refresh token 一致.
     * 任一失败抛 UNAUTHORIZED. 成功后旧的 refresh token 失效(被 Redis 覆盖).
     */
    public LoginResponse refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "refreshToken 缺失");
        }
        if (!jwtUtil.validateToken(refreshToken)) {
            throw new BusinessException(ResultCode.TOKEN_INVALID, "refreshToken 无效或已过期");
        }
        if (!jwtUtil.isRefreshToken(refreshToken)) {
            throw new BusinessException(ResultCode.TOKEN_INVALID, "不是 refresh token");
        }
        Long userId = jwtUtil.getUserId(refreshToken);
        String username = jwtUtil.getUsername(refreshToken);

        // 防御性校验: 当前 Redis 里保存的 refresh token 是否一致 (踢出/换密码等场景下 Redis 已被清掉)
        try {
            Object cached = redisTemplate.opsForValue().get(RedisConstants.REFRESH_TOKEN_PREFIX + userId);
            if (cached != null && !refreshToken.equals(cached.toString())) {
                throw new BusinessException(ResultCode.TOKEN_INVALID, "refreshToken 已被新 token 替换");
            }
        } catch (DataAccessException e) {
            log.warn("Redis unavailable when checking refresh token for userId={}", userId, e);
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        String newAccess = jwtUtil.generateAccessToken(user.getId(), username);
        String newRefresh = jwtUtil.generateRefreshToken(user.getId(), username);
        cacheRefreshTokenSafely(user.getId(), newRefresh);

        LoginResponse response = new LoginResponse();
        response.setAccessToken(newAccess);
        response.setRefreshToken(newRefresh);
        LoginResponse.UserInfo info = new LoginResponse.UserInfo();
        info.setId(user.getId());
        info.setUsername(user.getUsername());
        info.setNickname(user.getNickname());
        info.setAvatar(user.getAvatar());
        response.setUserInfo(info);
        return response;
    }

    private void cacheUserInfoSafely(User user) {
        try {
            redisTemplate.opsForValue().set(
                    RedisConstants.USER_INFO_PREFIX + user.getId(),
                    user,
                    7,
                    java.util.concurrent.TimeUnit.DAYS
            );
        } catch (DataAccessException e) {
            log.warn("Redis unavailable when caching user info for userId={}", user.getId(), e);
        }
    }

    private void cacheRefreshTokenSafely(Long userId, String refreshToken) {
        try {
            redisTemplate.opsForValue().set(
                    RedisConstants.REFRESH_TOKEN_PREFIX + userId,
                    refreshToken,
                    7,
                    java.util.concurrent.TimeUnit.DAYS
            );
        } catch (DataAccessException e) {
            log.warn("Redis unavailable when caching refresh token for userId={}", userId, e);
        }
    }
}
