package com.goeats.delivery.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis GEO 자료구조를 활용한 실시간 라이더 매칭 서비스.
 *
 * <p>Redis의 GEO(Geospatial) 기능으로 라이더의 실시간 위치를 관리하고,
 * 배달 요청 시 가장 가까운 라이더를 찾아 매칭합니다.</p>
 *
 * <p>동작 방식:
 * <ul>
 *   <li>updateRiderLocation(): 라이더 앱에서 주기적으로 위치를 업데이트</li>
 *   <li>findNearestRider(): 가게 좌표 기준 반경 내 가장 가까운 라이더를 검색</li>
 * </ul>
 * Redis GEO는 내부적으로 Sorted Set + Geohash를 사용하여
 * O(log(N)+M) 시간복잡도로 반경 검색을 수행합니다.</p>
 *
 * <p>★ Monolithic과의 차이:
 * - Monolithic: 실시간 위치 추적 불가, 단순 시뮬레이션(하드코딩된 라이더 배정)
 *   → Caffeine 로컬 캐시로는 여러 서버 간 위치 공유 불가
 * - MSA: Redis GEO로 실시간 위치 기반 매칭 가능
 *   → 모든 서비스 인스턴스가 Redis를 통해 같은 라이더 위치 데이터 공유
 *   → 수만 명의 라이더 위치를 실시간으로 관리하고 빠르게 검색 가능</p>
 */

/**
 * ★ MSA: Redis GEO for real-time rider location tracking.
 * This is NOT possible in monolithic with just Caffeine cache.
 *
 * Compare with Monolithic: Simple simulation (hardcoded rider assignment).
 * No real-time location tracking capability.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiderMatchingService {

    private final RedisTemplate<String, String> redisTemplate;
    /** Redis에 라이더 위치를 저장하는 GEO key */
    private static final String RIDER_GEO_KEY = "riders:location";

    /**
     * 라이더의 현재 위치를 Redis GEO에 업데이트합니다.
     * 라이더 앱에서 주기적으로(예: 10초마다) 호출됩니다.
     *
     * @param riderId 라이더 고유 ID
     * @param longitude 경도 (예: 127.0276 = 강남역)
     * @param latitude 위도 (예: 37.4979 = 강남역)
     */
    public void updateRiderLocation(String riderId, double longitude, double latitude) {
        redisTemplate.opsForGeo().add(RIDER_GEO_KEY,
                new Point(longitude, latitude), riderId);
        log.debug("Rider {} location updated: ({}, {})", riderId, longitude, latitude);
    }

    /**
     * 지정한 좌표에서 가장 가까운 라이더를 찾습니다.
     *
     * Redis GEORADIUS 명령을 사용하여 반경 내 라이더를 거리순으로 정렬하고,
     * 가장 가까운 1명을 반환합니다.
     *
     * @param longitude 기준점 경도 (가게 위치)
     * @param latitude 기준점 위도 (가게 위치)
     * @param radiusKm 검색 반경 (킬로미터)
     * @return 가장 가까운 라이더의 ID, 없으면 null
     */
    public String findNearestRider(double longitude, double latitude, double radiusKm) {
        // Redis GEORADIUS: 반경 내 라이더를 거리순 오름차순으로 검색, 1명만 반환
        GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                redisTemplate.opsForGeo().radius(RIDER_GEO_KEY,
                        new Circle(new Point(longitude, latitude),
                                new Distance(radiusKm, Metrics.KILOMETERS)),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .sortAscending()  // 거리 가까운 순으로 정렬
                                .limit(1));        // 가장 가까운 1명만 반환

        if (results != null && !results.getContent().isEmpty()) {
            String riderId = results.getContent().get(0).getContent().getName();
            log.info("Found nearest rider: {} within {}km", riderId, radiusKm);
            return riderId;
        }

        log.warn("No riders found within {}km", radiusKm);
        return null;
    }
}
