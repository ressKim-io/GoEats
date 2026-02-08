package com.goeats.store.service;

import com.goeats.common.exception.BusinessException;
import com.goeats.common.exception.ErrorCode;
import com.goeats.store.entity.Menu;
import com.goeats.store.repository.MenuRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MenuService {

    private final MenuRepository menuRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    @Cacheable(value = "menus", key = "#menuId")
    @CircuitBreaker(name = "storeDb", fallbackMethod = "getMenuFallback")
    public Menu getMenu(Long menuId) {
        return menuRepository.findById(menuId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MENU_NOT_FOUND));
    }

    @Cacheable(value = "menus:store", key = "#storeId")
    @CircuitBreaker(name = "storeDb", fallbackMethod = "getMenusByStoreFallback")
    public List<Menu> getMenusByStore(Long storeId) {
        return menuRepository.findByStoreIdAndAvailableTrue(storeId);
    }

    // ★ Fallback: try manual Redis cache → throw
    @SuppressWarnings("unused")
    private Menu getMenuFallback(Long menuId, Throwable t) {
        log.warn("Menu DB fallback for menuId={}: {}", menuId, t.getMessage());
        Object cached = redisTemplate.opsForValue().get("menus::" + menuId);
        if (cached instanceof Menu menu) {
            return menu;
        }
        throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @SuppressWarnings("unused")
    private List<Menu> getMenusByStoreFallback(Long storeId, Throwable t) {
        log.warn("Menu list fallback for storeId={}: {}", storeId, t.getMessage());
        return List.of();
    }
}
