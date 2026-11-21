package com.cvdcms.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Giới hạn tần suất cho endpoint đặt lịch công khai /api/public/booking.
 * Khóa theo IP client (remoteAddr), HTTP Session và SĐT chuẩn hóa (hash nội bộ)
 * để không bị vô hiệu hóa chỉ bằng cách thay đổi một trường trong request body.
 */
@Component
public class PublicBookingRateLimiter {

    public static final String RATE_LIMIT_MESSAGE =
            "Quý khách thao tác quá nhanh. Vui lòng thử lại sau ít phút.";

    private static final int MAX_TRACKED_KEYS = 5000;

    private final ConcurrentHashMap<String, Deque<Instant>> ipBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<Instant>> sessionBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<Instant>> phoneBuckets = new ConcurrentHashMap<>();

    private final int ipMaxRequests;
    private final long ipWindowSeconds;
    private final int sessionMaxRequests;
    private final long sessionWindowSeconds;
    private final int phoneMaxRequests;
    private final long phoneWindowSeconds;
    private Clock clock;

    @Autowired
    public PublicBookingRateLimiter(
            @Value("${booking.public.rate-limit.ip-max-requests:5}") int ipMaxRequests,
            @Value("${booking.public.rate-limit.ip-window-seconds:60}") long ipWindowSeconds,
            @Value("${booking.public.rate-limit.session-max-requests:5}") int sessionMaxRequests,
            @Value("${booking.public.rate-limit.session-window-seconds:60}") long sessionWindowSeconds,
            @Value("${booking.public.rate-limit.phone-max-requests:3}") int phoneMaxRequests,
            @Value("${booking.public.rate-limit.phone-window-seconds:600}") long phoneWindowSeconds) {
        this.ipMaxRequests = Math.max(1, ipMaxRequests);
        this.ipWindowSeconds = Math.max(1L, ipWindowSeconds);
        this.sessionMaxRequests = Math.max(1, sessionMaxRequests);
        this.sessionWindowSeconds = Math.max(1L, sessionWindowSeconds);
        this.phoneMaxRequests = Math.max(1, phoneMaxRequests);
        this.phoneWindowSeconds = Math.max(1L, phoneWindowSeconds);
        this.clock = Clock.systemUTC();
    }

    public void setClock(Clock clock) {
        if (clock != null) {
            this.clock = clock;
        }
    }

    public void clear() {
        ipBuckets.clear();
        sessionBuckets.clear();
        phoneBuckets.clear();
    }

    /**
     * Kiểm tra rate limit ở lớp vận chuyển (IP &amp; Session) độc lập hoàn toàn với payload request.
     * Chỉ dùng remoteAddr thực sự, không tin X-Forwarded-For từ client.
     */
    public void checkIpAndSession(HttpServletRequest request, HttpSession session) {
        Instant now = clock.instant();
        if (request != null) {
            String remoteAddr = request.getRemoteAddr();
            if (remoteAddr != null && !remoteAddr.isBlank()) {
                consumeSlot(ipBuckets, "ip:" + remoteAddr.trim(), now, ipWindowSeconds, ipMaxRequests);
            }
        }
        if (session != null && session.getId() != null && !session.getId().isBlank()) {
            consumeSlot(sessionBuckets, "sess:" + session.getId(), now, sessionWindowSeconds, sessionMaxRequests);
        }
    }

    /**
     * Kiểm tra rate limit bổ sung theo số điện thoại chuẩn hóa (băm SHA-256 trước khi làm key).
     */
    public void checkPhone(String normalizedPhone) {
        if (normalizedPhone == null || normalizedPhone.isBlank()) {
            return;
        }
        Instant now = clock.instant();
        consumeSlot(phoneBuckets, "phone:" + hashKey(normalizedPhone), now, phoneWindowSeconds, phoneMaxRequests);
    }

    private void consumeSlot(ConcurrentHashMap<String, Deque<Instant>> store,
                             String key,
                             Instant now,
                             long windowSeconds,
                             int maxRequests) {
        if (store.size() > MAX_TRACKED_KEYS) {
            pruneExpired(store, now.minusSeconds(windowSeconds));
        }
        Instant cutoff = now.minusSeconds(windowSeconds);
        Deque<Instant> timestamps = store.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && !timestamps.peekFirst().isAfter(cutoff)) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= maxRequests) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, RATE_LIMIT_MESSAGE);
            }
            timestamps.addLast(now);
        }
    }

    private void pruneExpired(ConcurrentHashMap<String, Deque<Instant>> store, Instant cutoff) {
        store.entrySet().removeIf(entry -> {
            Deque<Instant> q = entry.getValue();
            synchronized (q) {
                while (!q.isEmpty() && !q.peekFirst().isAfter(cutoff)) {
                    q.pollFirst();
                }
                return q.isEmpty();
            }
        });
    }

    private static String hashKey(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
