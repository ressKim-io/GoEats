package com.goeats.store.service;

import com.goeats.common.exception.BusinessException;
import com.goeats.common.exception.ErrorCode;
import com.goeats.store.entity.Menu;
import com.goeats.store.entity.Store;
import com.goeats.store.repository.MenuRepository;
import com.goeats.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreService {

    private final StoreRepository storeRepository;
    private final MenuRepository menuRepository;

    /**
     * ★ Monolithic: @Cacheable with Caffeine (local in-memory cache).
     * No external dependency. Fast but only works within a single JVM.
     *
     * Compare with MSA: @Cacheable with Redis (distributed cache).
     * Works across multiple service instances but requires Redis cluster.
     */
    @Cacheable(value = "stores", key = "#id")
    public Store getStore(Long id) {
        return storeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
    }

    @Cacheable(value = "storeMenus", key = "#storeId")
    public List<Menu> getMenusByStore(Long storeId) {
        return menuRepository.findByStoreId(storeId);
    }

    public Store getStoreWithMenus(Long id) {
        return storeRepository.findWithMenusById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
    }

    public Menu getMenu(Long menuId) {
        return menuRepository.findById(menuId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MENU_NOT_FOUND));
    }
}
