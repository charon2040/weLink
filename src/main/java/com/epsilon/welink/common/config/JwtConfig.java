package com.epsilon.welink.common.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

@Configuration
public class JwtConfig {

    @Value("${welink.jwt.secret}")
    private String secret;

    @Value("${welink.jwt.access-token-expiration}")
    private Long accessTokenExpiration;

    @Value("${welink.jwt.refresh-token-expiration}")
    private Long refreshTokenExpiration;

    // 配置JWT签名密钥，长度至少为32字节
    @Bean
    public SecretKey signingKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String getSecret() {
        return secret;
    }

    public Long getAccessTokenExpiration() {
        return accessTokenExpiration;
    }

    public Long getRefreshTokenExpiration() {
        return refreshTokenExpiration;
    }
}
