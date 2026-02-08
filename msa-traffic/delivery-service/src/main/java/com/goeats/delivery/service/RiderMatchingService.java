package com.goeats.delivery.service;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 라이더 매칭 서비스 - Redis GEO를 활용한 위치 기반 라이더 매칭.
 *
 * <p>라이더의 실시간 위치를 Redis GEO 자료구조에 저장하고,
 * 주문 발생 시 가장 가까운 라이더를 검색하여 매칭한다.</p>
 *
 * <h3>Redis GEO 자료구조</h3>
 * <p>Redis의 GEOADD/GEORADIUS 명령을 사용하여 위도/경도 기반 반경 검색을 수행한다.
 * 시간 복잡도 O(N+log(M))으로 대규모 라이더 풀에서도 빠른 검색이 가능하다.</p>
 *
 * <h3>동작 흐름</h3>
 * <pre>
 * 1. 라이더 앱: 주기적으로 GPS 위치를 서버에 전송 → updateRiderLocation()
 * 2. 배달 생성 시: findNearestRider(주문 좌표, 반경) → 가장 가까운 라이더 ID 반환
 * 3. 매칭 성공: DeliveryService에서 Fencing Token과 함께 라이더 정보 DB 저장
 * </pre>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>모놀리식에서는 DB 쿼리(ST_Distance 등)로 라이더를 검색하여 DB 부하가 크다.
 * MSA-Traffic에서는 Redis GEO로 인메모리 검색하여 밀리초 단위 응답이 가능하다.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>기본 MSA에서는 Bulkhead 없이 라이더 매칭을 수행하여, 트래픽 폭주 시
 * Redis 연결 풀이 고갈되고 다른 Redis 작업(캐시, 분산 락 등)에도 영향을 줄 수 있다.
 * Traffic 버전에서는 @Bulkhead로 동시 Redis GEO 쿼리 수를 제한하여
 * 스레드 풀 고갈을 방지한다.</p>
 *
 * ★ Traffic MSA: Rider Matching with Bulkhead isolation
 *
 * @Bulkhead prevents too many concurrent Redis GEO queries
 * from exhausting the thread pool under traffic spikes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiderMatchingService {

    private final RedisTemplate<String, String> redisTemplate;
    private static final String RIDER_GEO_KEY = "riders:location";  // Redis GEO 키: 라이더 위치 저장소

    /**
     * 라이더 위치 업데이트 - 라이더 앱에서 주기적으로 호출.
     *
     * <p>Redis GEOADD 명령으로 라이더의 최신 GPS 좌표를 저장한다.
     * 기존 위치가 있으면 덮어쓰기(upsert)된다.</p>
     *
     * @param riderId 라이더 고유 ID
     * @param longitude 경도 (예: 127.0276 = 서울 강남)
     * @param latitude 위도 (예: 37.4979 = 서울 강남)
     */
    public void updateRiderLocation(String riderId, double longitude, double latitude) {
        // Redis GEOADD riders:location {longitude} {latitude} {riderId}
        redisTemplate.opsForGeo().add(RIDER_GEO_KEY,
                new Point(longitude, latitude), riderId);
        log.debug("Rider {} location updated: ({}, {})", riderId, longitude, latitude);
    }

    /**
     * 가장 가까운 라이더 검색 - 주문 발생 시 호출.
     *
     * <p>Redis GEORADIUS 명령으로 지정된 좌표 반경 내에서 가장 가까운 라이더 1명을 반환한다.
     * sortAscending()으로 거리순 정렬, limit(1)로 가장 가까운 1명만 선택한다.</p>
     *
     * <p>@Bulkhead: 동시 GEO 쿼리 수를 제한하여 Redis 연결 풀 고갈 방지</p>
     *
     * @param longitude 주문 위치 경도
     * @param latitude 주문 위치 위도
     * @param radiusKm 검색 반경 (km)
     * @return 가장 가까운 라이더 ID (없으면 null)
     */
    @Bulkhead(name = "riderMatching")  // 동시 Redis GEO 쿼리 수 제한 (스레드 풀 고갈 방지)
    public String findNearestRider(double longitude, double latitude, double radiusKm) {
        // Redis GEORADIUS riders:location {longitude} {latitude} {radiusKm} km ASC COUNT 1
        GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                redisTemplate.opsForGeo().radius(RIDER_GEO_KEY,
                        new Circle(new Point(longitude, latitude),
                                new Distance(radiusKm, Metrics.KILOMETERS)),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .sortAscending()  // 거리순 오름차순 정렬 (가장 가까운 순)
                                .limit(1));        // 1명만 선택

        if (results != null && !results.getContent().isEmpty()) {
            String riderId = results.getContent().get(0).getContent().getName();
            log.info("Found nearest rider: {} within {}km", riderId, radiusKm);
            return riderId;
        }

        log.warn("No riders found within {}km", radiusKm);
        return null;  // 반경 내 라이더 없음 → 배달은 WAITING 상태 유지
    }
}
