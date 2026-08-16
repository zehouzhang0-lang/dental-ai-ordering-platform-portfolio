package com.yuri.aiorder.ai;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AiExternalAlertScheduler {

    private final AiExternalAlertSenderService senderService;
    private final AiGatewayProperties properties;

    public AiExternalAlertScheduler(AiExternalAlertSenderService senderService, AiGatewayProperties properties) {
        this.senderService = senderService;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${app.ai.external-alert.scheduler-fixed-delay-millis:60000}",
            initialDelayString = "${app.ai.external-alert.scheduler-initial-delay-millis:60000}")
    public int dispatchPendingAlerts() {
        AiGatewayProperties.ExternalAlert externalAlert = properties.getExternalAlert();
        if (!externalAlert.isSchedulerEnabled()) {
            return 0;
        }
        return senderService.sendPendingAlerts(externalAlert.getSchedulerBatchSize());
    }
}
