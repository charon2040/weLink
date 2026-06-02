package com.epsilon.welink.gateway.interceptor;

import com.epsilon.welink.common.exception.BusinessException;
import com.epsilon.welink.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 保护 /internal/** /admin/** /drain 等管理路径, 要求共享密钥头 X-Internal-Secret.
 * 生产部署用 K8s Secret 注入。本地开发默认密钥与 application.properties 一致.
 */
@Slf4j
@Component
public class InternalAuthInterceptor implements HandlerInterceptor {

    @Value("${welink.internal.secret:welink-internal-default-secret-change-me}")
    private String expectedSecret;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String provided = request.getHeader("X-Internal-Secret");
        if (provided == null || !provided.equals(expectedSecret)) {
            log.warn("Internal endpoint access denied: path={} ip={}", request.getRequestURI(), request.getRemoteAddr());
            throw new BusinessException(ResultCode.UNAUTHORIZED, "Internal endpoint requires X-Internal-Secret header");
        }
        return true;
    }
}
