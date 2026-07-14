package com.demo.upimesh.config;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis is used for three distinct things in the event-driven profile:
 *   1. Duplicate packet / idempotency cache  (cache.RedisIdempotencyStore)
 *   2. Rate limiting at the Gateway          (cache.RedisRateLimiterService)
 *   3. (Optional extension point) session/short-lived lookup caches
 *
 * A single StringRedisTemplate is enough since all values we store are
 * simple strings/counters — no need for a full ObjectMapper-backed template.
 */
@Configuration
@Profile("event-driven")
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
