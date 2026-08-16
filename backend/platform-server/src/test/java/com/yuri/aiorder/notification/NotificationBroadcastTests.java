package com.yuri.aiorder.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@SpringBootTest(properties = "app.notification.instance-id=test-instance")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class NotificationBroadcastTests {

    @Autowired
    private NotificationPushService pushService;

    @Autowired
    private CapturingNotificationBroadcaster broadcaster;

    @Test
    void pushPublishesBroadcastEvenWhenUserHasNoLocalSession() {
        pushService.pushToUser(10001L, 20002L, "{\"event\":\"TEST\"}");

        assertThat(broadcaster.messages())
                .containsExactly(new NotificationBroadcastMessage(10001L, 20002L, "{\"event\":\"TEST\"}", "test-instance"));
    }

    @Test
    void redisListenerIgnoresOwnMessageAndDeliversRemoteMessageLocally() throws Exception {
        NotificationPushService localPush = mock(NotificationPushService.class);
        NotificationRedisBroadcastListener listener = new NotificationRedisBroadcastListener(
                new ObjectMapper(),
                localPush,
                "test-instance");

        ObjectMapper objectMapper = new ObjectMapper();
        String ownMessage = objectMapper.writeValueAsString(new NotificationBroadcastMessage(
                10001L,
                20002L,
                "{\"event\":\"OWN\"}",
                "test-instance"));
        String remoteMessage = objectMapper.writeValueAsString(new NotificationBroadcastMessage(
                10001L,
                20003L,
                "{\"event\":\"REMOTE\"}",
                "other-instance"));

        listener.handleMessage(ownMessage);
        verifyNoInteractions(localPush);

        listener.handleMessage(remoteMessage);
        verify(localPush).pushLocalToUser(10001L, 20003L, "{\"event\":\"REMOTE\"}");
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        CapturingNotificationBroadcaster capturingNotificationBroadcaster() {
            return new CapturingNotificationBroadcaster();
        }
    }

    static class CapturingNotificationBroadcaster implements NotificationBroadcaster {

        private final List<NotificationBroadcastMessage> messages = new ArrayList<>();

        @Override
        public void broadcast(NotificationBroadcastMessage message) {
            messages.add(message);
        }

        List<NotificationBroadcastMessage> messages() {
            return messages;
        }
    }
}
