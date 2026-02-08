package com.goeats.delivery.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

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
    private static final String RIDER_GEO_KEY = "riders:location";

    public void updateRiderLocation(String riderId, double longitude, double latitude) {
        redisTemplate.opsForGeo().add(RIDER_GEO_KEY,
                new Point(longitude, latitude), riderId);
        log.debug("Rider {} location updated: ({}, {})", riderId, longitude, latitude);
    }

    public String findNearestRider(double longitude, double latitude, double radiusKm) {
        GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                redisTemplate.opsForGeo().radius(RIDER_GEO_KEY,
                        new Circle(new Point(longitude, latitude),
                                new Distance(radiusKm, Metrics.KILOMETERS)),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .sortAscending().limit(1));

        if (results != null && !results.getContent().isEmpty()) {
            String riderId = results.getContent().get(0).getContent().getName();
            log.info("Found nearest rider: {} within {}km", riderId, radiusKm);
            return riderId;
        }

        log.warn("No riders found within {}km", radiusKm);
        return null;
    }
}
