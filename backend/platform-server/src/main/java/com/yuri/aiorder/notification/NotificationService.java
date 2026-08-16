package com.yuri.aiorder.notification;

import com.yuri.aiorder.common.BootstrapIdentity;
import java.util.List;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final JdbcClient jdbcClient;

    public NotificationService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<NotificationResponse> listNotifications(BootstrapIdentity identity, boolean unreadOnly, int limit) {
        long userId = requireUserId(identity);
        int boundedLimit = boundedLimit(limit);
        String unreadFilter = unreadOnly ? "AND un.read_at IS NULL" : "";
        return jdbcClient.sql("""
                        SELECT un.user_notification_id,
                               ne.event_id,
                               ne.event_type,
                               ne.order_id,
                               JSON_UNQUOTE(JSON_EXTRACT(ne.payload, '$.orderNo')) AS order_no,
                               JSON_UNQUOTE(JSON_EXTRACT(ne.payload, '$.message')) AS message,
                               un.read_at,
                               un.delivered_at,
                               un.created_at
                        FROM user_notification un
                        JOIN notification_event ne ON ne.event_id = un.event_id
                        WHERE un.user_id = :userId
                          %s
                        ORDER BY un.created_at DESC, un.user_notification_id DESC
                        LIMIT :limit
                        """.formatted(unreadFilter))
                .param("userId", userId)
                .param("limit", boundedLimit)
                .query((rs, rowNum) -> new NotificationResponse(
                        rs.getLong("user_notification_id"),
                        rs.getLong("event_id"),
                        rs.getString("event_type"),
                        rs.getObject("order_id", Long.class),
                        rs.getString("order_no"),
                        rs.getString("message"),
                        rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toLocalDateTime(),
                        rs.getTimestamp("delivered_at") == null ? null : rs.getTimestamp("delivered_at").toLocalDateTime(),
                        rs.getTimestamp("created_at").toLocalDateTime()))
                .list();
    }

    public UnreadCountResponse unreadCount(BootstrapIdentity identity) {
        long userId = requireUserId(identity);
        long count = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM user_notification
                        WHERE user_id = :userId
                          AND read_at IS NULL
                        """)
                .param("userId", userId)
                .query(Long.class)
                .single();
        return new UnreadCountResponse(count);
    }

    public NotificationResponse markRead(BootstrapIdentity identity, long notificationId) {
        long userId = requireUserId(identity);
        int updated = jdbcClient.sql("""
                        UPDATE user_notification
                        SET read_at = COALESCE(read_at, CURRENT_TIMESTAMP(3))
                        WHERE user_notification_id = :notificationId
                          AND user_id = :userId
                        """)
                .param("notificationId", notificationId)
                .param("userId", userId)
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "notification not found");
        }
        return loadNotification(userId, notificationId);
    }

    public MarkAllReadResponse markAllRead(BootstrapIdentity identity) {
        long userId = requireUserId(identity);
        int updated = jdbcClient.sql("""
                        UPDATE user_notification
                        SET read_at = CURRENT_TIMESTAMP(3)
                        WHERE user_id = :userId
                          AND read_at IS NULL
                        """)
                .param("userId", userId)
                .update();
        return new MarkAllReadResponse(updated);
    }

    private NotificationResponse loadNotification(long userId, long notificationId) {
        try {
            return jdbcClient.sql("""
                            SELECT un.user_notification_id,
                                   ne.event_id,
                                   ne.event_type,
                                   ne.order_id,
                                   JSON_UNQUOTE(JSON_EXTRACT(ne.payload, '$.orderNo')) AS order_no,
                                   JSON_UNQUOTE(JSON_EXTRACT(ne.payload, '$.message')) AS message,
                                   un.read_at,
                                   un.delivered_at,
                                   un.created_at
                            FROM user_notification un
                            JOIN notification_event ne ON ne.event_id = un.event_id
                            WHERE un.user_notification_id = :notificationId
                              AND un.user_id = :userId
                            """)
                    .param("notificationId", notificationId)
                    .param("userId", userId)
                    .query((rs, rowNum) -> new NotificationResponse(
                            rs.getLong("user_notification_id"),
                            rs.getLong("event_id"),
                            rs.getString("event_type"),
                            rs.getObject("order_id", Long.class),
                            rs.getString("order_no"),
                            rs.getString("message"),
                            rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toLocalDateTime(),
                            rs.getTimestamp("delivered_at") == null ? null : rs.getTimestamp("delivered_at").toLocalDateTime(),
                            rs.getTimestamp("created_at").toLocalDateTime()))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "notification not found", ex);
        }
    }

    private long requireUserId(BootstrapIdentity identity) {
        if (identity.userId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "user id is required for notifications");
        }
        return identity.userId();
    }

    private int boundedLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
