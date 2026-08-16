package com.yuri.aiorder.notification;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.BearerTokenService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class NotificationRestTests {

    private static final long DOCTOR_USER_ID = 9911L;
    private static final long OTHER_USER_ID = 9912L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private BearerTokenService tokenService;

    private long clinicId;
    private long notificationId;
    private String doctorToken;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        jdbcClient.sql("INSERT INTO clinic (clinic_name) VALUES (:clinicName)")
                .param("clinicName", "通知测试诊所-" + suffix)
                .update();
        clinicId = lastInsertId();
        notificationId = createNotification(DOCTOR_USER_ID, "NR" + suffix.substring(0, 12), "账单已上传");
        createNotification(OTHER_USER_ID, "OTHER-" + suffix.substring(0, 8), "他人通知");
        doctorToken = tokenService.issue(new BootstrapIdentity(UserRole.DOCTOR, DOCTOR_USER_ID, clinicId));
    }

    @Test
    void currentUserListsOwnNotificationsAndCanMarkRead() throws Exception {
        mockMvc.perform(get("/notifications")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].notification_id").value(notificationId))
                .andExpect(jsonPath("$.data[0].event").value("BILL_UPLOADED"))
                .andExpect(jsonPath("$.data[0].message").value("账单已上传"))
                .andExpect(content().string(not(containsString("内部通知备注"))))
                .andExpect(content().string(not(containsString("他人通知"))));

        mockMvc.perform(get("/notifications/unread-count")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unread_count").value(1));

        mockMvc.perform(post("/notifications/{notificationId}/read", notificationId)
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notification_id").value(notificationId))
                .andExpect(jsonPath("$.data.read_at").isNotEmpty());

        mockMvc.perform(get("/notifications/unread-count")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unread_count").value(0));
    }

    @Test
    void currentUserCanMarkAllOwnNotificationsRead() throws Exception {
        mockMvc.perform(post("/notifications/read-all")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated_count").value(1));

        mockMvc.perform(get("/notifications")
                        .param("unread_only", "true")
                        .header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    private long createNotification(long userId, String orderNo, String message) {
        String payload = """
                {"event":"BILL_UPLOADED","orderId":123,"orderNo":"%s","message":"%s"}
                """.formatted(orderNo, message);
        jdbcClient.sql("""
                        INSERT INTO notification_event
                            (event_type, audience_role, payload, delivery_status)
                        VALUES
                            ('BILL_UPLOADED', 'DOCTOR', CAST(:payload AS JSON), 'PENDING')
                        """)
                .param("payload", payload)
                .update();
        long eventId = lastInsertId();
        jdbcClient.sql("""
                        INSERT INTO user_notification (event_id, user_id)
                        VALUES (:eventId, :userId)
                        """)
                .param("eventId", eventId)
                .param("userId", userId)
                .update();
        return jdbcClient.sql("""
                        SELECT user_notification_id
                        FROM user_notification
                        WHERE event_id = :eventId
                          AND user_id = :userId
                        """)
                .param("eventId", eventId)
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    private long lastInsertId() {
        return jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }
}
