package com.goeats.store.config;

import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

/**
 * Redis 설정 클래스 - 캐시 직렬화, TTL, RedisTemplate 설정.
 *
 * <p>Store Service의 캐싱 인프라를 구성한다. JSON 직렬화, 캐시별 TTL 차별화,
 * 수동 캐시 조작용 RedisTemplate을 설정한다.</p>
 *
 * <h3>주요 설정 항목</h3>
 * <ul>
 *   <li><b>RedisTemplate</b>: Cache Warming, Fallback 등에서 수동으로 Redis를 조작할 때 사용</li>
 *   <li><b>CacheManager</b>: @Cacheable 어노테이션의 자동 캐시 관리에 사용</li>
 *   <li><b>JSON 직렬화</b>: Store/Menu 같은 복잡한 객체를 Redis에 저장하기 위해 필요</li>
 *   <li><b>캐시별 TTL</b>: 데이터 특성에 따라 TTL을 차별화하여 메모리 효율성 최적화</li>
 * </ul>
 *
 * <h3>캐시별 TTL 전략</h3>
 * <pre>
 * stores       (30분) - 가게 기본 정보는 잘 변하지 않음 → 긴 TTL
 * stores:detail(15분) - 메뉴 포함 상세 정보는 변경 빈도가 높음 → 중간 TTL
 * menus        (10분) - 메뉴는 가격/재고 변경이 빈번함 → 짧은 TTL
 * menus:store  (10분) - 가게별 메뉴 목록 → 짧은 TTL
 * </pre>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>모놀리식에서는 Caffeine(로컬 캐시)를 사용하므로 직렬화가 필요 없다.
 * 객체 참조를 그대로 저장하기 때문이다. Redis는 네트워크를 통해 데이터를 전송하므로
 * JSON 직렬화/역직렬화가 필수적이다.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>기본 MSA에서는 기본 RedisCacheManager + String 직렬화를 사용한다.
 * Traffic 버전에서는 JSON 직렬화(복잡한 객체 지원)와 캐시별 TTL 차별화를 적용하여
 * 캐시 효율성을 극대화한다.</p>
 *
 * ★ Traffic MSA: Redis Cache Configuration
 *
 * vs Basic MSA: Default RedisCacheManager with string serialization
 *
 * Enhancements:
 * - JSON serialization for complex objects (Store, Menu)
 * - Per-cache TTL configuration
 * - RedisTemplate for manual cache operations (fallback)
 */
@Configuration
public class RedisConfig {

    /**
     * RedisTemplate 빈 설정 - 수동 Redis 조작용.
     *
     * <p>Cache Warming, CircuitBreaker Fallback 등에서 @Cacheable 외에
     * 직접 Redis에 읽기/쓰기 할 때 사용한다.</p>
     *
     * <h4>직렬화 설정</h4>
     * <ul>
     *   <li>Key: StringRedisSerializer (문자열 키)</li>
     *   <li>Value: GenericJackson2JsonRedisSerializer (JSON 직렬화)</li>
     *   <li>HashKey/HashValue: 동일 직렬화 적용</li>
     * </ul>
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());          // 키: 문자열
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());  // 값: JSON
        template.setHashKeySerializer(new StringRedisSerializer());      // 해시 키: 문자열
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());  // 해시 값: JSON
        return template;
    }

    /**
     * CacheManager 빈 설정 - @Cacheable 어노테이션의 자동 캐시 관리.
     *
     * <p>기본 TTL 10분에 캐시별로 TTL을 차별화하여 설정한다.
     * 자주 변하는 데이터(메뉴)는 짧은 TTL, 안정적인 데이터(가게)는 긴 TTL을 적용한다.</p>
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory factory) {
        // 기본 캐시 설정: JSON 직렬화 + TTL 10분
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()))
                .entryTtl(Duration.ofMinutes(10));  // 기본 TTL: 10분

        // ★ Per-cache TTL: 데이터 특성에 따라 TTL 차별화
        // 자주 변하는 데이터 = 짧은 TTL, 안정적 데이터 = 긴 TTL
        Map<String, RedisCacheConfiguration> cacheConfigs = Map.of(
                "stores", defaultConfig.entryTtl(Duration.ofMinutes(30)),       // 가게 기본 정보 (30분)
                "stores:detail", defaultConfig.entryTtl(Duration.ofMinutes(15)), // 가게+메뉴 상세 (15분)
                "menus", defaultConfig.entryTtl(Duration.ofMinutes(10)),         // 개별 메뉴 (10분)
                "menus:store", defaultConfig.entryTtl(Duration.ofMinutes(10))    // 가게별 메뉴 목록 (10분)
        );

        return RedisCacheManager.builder(factory)
                .cacheDefaults(defaultConfig)               // 기본 설정 적용
                .withInitialCacheConfigurations(cacheConfigs) // 캐시별 커스텀 TTL 적용
                .build();
    }
}
