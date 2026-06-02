package com.epsilon.welink.common.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把所有 Long / long 类型序列化为 JSON String, 避免雪花 ID (19 位, ~2 × 10^18)
 * 超过 JavaScript Number.MAX_SAFE_INTEGER (2^53-1 ≈ 9 × 10^15) 导致前端精度丢失.
 * <p>
 * 例: 后端 groupId=2059317956154556417 → 不配置时 JSON number, 前端 parse 成 2059317956154556400 (尾部位丢失).
 * 配置后 → JSON string "2059317956154556417", 前端按字符串处理, 0 精度损失.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule();
            module.addSerializer(Long.class, ToStringSerializer.instance);
            module.addSerializer(Long.TYPE, ToStringSerializer.instance);
            builder.modulesToInstall(module);
        };
    }
}
