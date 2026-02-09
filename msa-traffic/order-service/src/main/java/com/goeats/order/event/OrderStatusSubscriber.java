package com.goeats.order.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * Redis Pub/Sub 주문 상태 구독자 - 실시간 알림 수신
 *
 * <h3>역할</h3>
 * <p>"order:status" 채널에서 주문 상태 변경 메시지를 수신하여 처리한다.
 * 교육용으로 로그를 출력하며, 실무에서는 WebSocket/SSE로 클라이언트에게 전달한다.</p>
 *
 * <h3>실무 확장 포인트</h3>
 * <pre>
 *   현재 (교육용):
 *     Redis Pub/Sub → log.info() (로그 출력만)
 *
 *   실무 확장:
 *     Redis Pub/Sub → WebSocketSession.sendMessage() (실시간 푸시)
 *     Redis Pub/Sub → SseEmitter.send() (Server-Sent Events)
 *     Redis Pub/Sub → FCM/APNs → 모바일 푸시 알림
 * </pre>
 *
 * <h3>★ Fire-and-Forget 특성</h3>
 * <p>이 구독자가 메시지를 받지 못해도 시스템에 영향 없음.
 * 주문 상태 변경의 "진짜" 처리는 Kafka(Spring Cloud Stream)가 담당.
 * Redis Pub/Sub은 "추가적인 실시간 알림"만 담당.</p>
 *
 * <h3>★ MessageListener 인터페이스</h3>
 * <p>Spring Data Redis의 MessageListener를 구현하여 Redis SUBSCRIBE 메시지를 수신.
 * RedisPubSubConfig에서 MessageListenerContainer에 등록된다.</p>
 *
 * ★ Redis Pub/Sub subscriber for real-time order status notifications
 *
 * In production, replace log.info with:
 *   - WebSocket push to connected clients
 *   - SSE (Server-Sent Events) stream
 *   - Mobile push notification (FCM/APNs)
 */
@Slf4j
@Component
public class OrderStatusSubscriber implements MessageListener {

    /**
     * Redis SUBSCRIBE 메시지 수신 콜백.
     *
     * <p>MessageListenerContainer가 "order:status" 채널의 메시지를 수신하면
     * 이 메서드가 호출된다.</p>
     *
     * @param message Redis 메시지 (body = JSON 문자열, channel = "order:status")
     * @param pattern 구독 패턴 (패턴 구독 시 사용, 여기서는 null)
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody());
        String channel = new String(message.getChannel());

        log.info("[Redis Pub/Sub] Received on channel={}: {}", channel, body);

        // ★ Production extension point:
        // webSocketHandler.broadcast(body);
        // sseEmitterManager.send(body);
    }
}
