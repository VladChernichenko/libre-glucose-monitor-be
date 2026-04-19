package che.glucosemonitorbe.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Per-cache specs tuned for Nightscout traffic patterns.
 * - nightscoutCredentials: user→config lookups; long TTL, config rarely changes (evicted on write).
 * - nightscoutEntries: upstream Nightscout responses; short TTL to absorb poll bursts without staleness.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String CACHE_NIGHTSCOUT_CREDENTIALS = "nightscoutCredentials";
    public static final String CACHE_NIGHTSCOUT_ENTRIES     = "nightscoutEntries";
    public static final String CACHE_NUTRITION_API          = "nutritionApiResponses";
    public static final String CACHE_CGM_READINGS           = "cgmReadings";
    public static final String CACHE_LLM_RESPONSES          = "llmResponses";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setAsyncCacheMode(false);
        manager.setAllowNullValues(true);

        manager.registerCustomCache(CACHE_NIGHTSCOUT_CREDENTIALS,
                Caffeine.newBuilder()
                        .maximumSize(50_000)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .recordStats()
                        .build());

        manager.registerCustomCache(CACHE_NIGHTSCOUT_ENTRIES,
                Caffeine.newBuilder()
                        .maximumSize(50_000)
                        .expireAfterWrite(30, TimeUnit.SECONDS)
                        .recordStats()
                        .build());

        manager.registerCustomCache(CACHE_NUTRITION_API,
                Caffeine.newBuilder()
                        .maximumSize(10_000)
                        .expireAfterWrite(7, TimeUnit.DAYS)
                        .recordStats()
                        .build());

        manager.registerCustomCache(CACHE_CGM_READINGS,
                Caffeine.newBuilder()
                        .maximumSize(50_000)
                        .expireAfterWrite(6, TimeUnit.MINUTES)
                        .recordStats()
                        .build());

        manager.registerCustomCache(CACHE_LLM_RESPONSES,
                Caffeine.newBuilder()
                        .maximumSize(5_000)
                        .expireAfterWrite(24, TimeUnit.HOURS)
                        .recordStats()
                        .build());

        return manager;
    }
}
