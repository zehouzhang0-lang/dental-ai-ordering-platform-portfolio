package com.yuri.aiorder;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuri.aiorder.common.auth.BearerIdentityFilter;
import com.yuri.aiorder.ruoyi.RuoyiRuntimeBridge;
import com.yuri.aiorder.ruoyi.RuoyiRuntimeBridgeInfoContributor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;
import org.springframework.core.annotation.Order;

class RuoyiRuntimeBridgeTests {

    @Test
    void shouldUsePinnedRuoyiFilterOrderInExistingBearerRuntime() {
        Order order = BearerIdentityFilter.class.getAnnotation(Order.class);

        assertThat(order).isNotNull();
        assertThat(order.value()).isEqualTo(RuoyiRuntimeBridge.BEARER_FILTER_ORDER);
        assertThat(RuoyiRuntimeBridge.BEARER_FILTER_ORDER).isEqualTo(-99);
    }

    @Test
    void shouldExposeNonSensitiveIncrementalBridgeStatus() {
        Info.Builder builder = new Info.Builder();
        new RuoyiRuntimeBridgeInfoContributor().contribute(builder);

        @SuppressWarnings("unchecked")
        var detail = (java.util.Map<String, Object>) builder.build().getDetails().get("ruoyiRuntimeBridge");
        assertThat(detail)
                .containsEntry("mode", "incremental")
                .containsEntry("sourceRevision", "2026.06-SNAPSHOT")
                .containsEntry("sourceCommit", "ec3f7cbf73e88514a70a6b59d365092ee470603d")
                .containsEntry("replacesExistingAuth", false);
    }
}
