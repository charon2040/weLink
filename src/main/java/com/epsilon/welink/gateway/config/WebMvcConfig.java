package com.epsilon.welink.gateway.config;

import com.epsilon.welink.gateway.interceptor.InternalAuthInterceptor;
import com.epsilon.welink.gateway.interceptor.JwtInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;
    private final InternalAuthInterceptor internalAuthInterceptor;

    public WebMvcConfig(JwtInterceptor jwtInterceptor, InternalAuthInterceptor internalAuthInterceptor) {
        this.jwtInterceptor = jwtInterceptor;
        this.internalAuthInterceptor = internalAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 用户级 JWT 拦截器: 所有 /api/v1/** 业务接口都要登录, 注册/登录/refresh/文件下载 豁免
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns(
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/auth/refresh",
                        "/api/v1/files/**"   // GET /files/{fileId} 与 /files/{fileId}/meta 都豁免 JWT (供 img/href 直接加载)
                );

        // 管理/内部接口共享密钥拦截器: /internal/** + /admin/** + /drain
        registry.addInterceptor(internalAuthInterceptor)
                .addPathPatterns("/internal/**", "/admin/**", "/drain");
    }
}
