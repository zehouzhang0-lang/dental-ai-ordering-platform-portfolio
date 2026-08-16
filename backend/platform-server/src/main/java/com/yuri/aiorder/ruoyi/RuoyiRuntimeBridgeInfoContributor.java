package com.yuri.aiorder.ruoyi;

import java.util.Map;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

@Component
public class RuoyiRuntimeBridgeInfoContributor implements InfoContributor {

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("ruoyiRuntimeBridge", Map.of(
                "mode", RuoyiRuntimeBridge.MODE,
                "sourceRevision", RuoyiRuntimeBridge.SOURCE_REVISION,
                "sourceCommit", RuoyiRuntimeBridge.SOURCE_COMMIT,
                "bearerFilterOrder", RuoyiRuntimeBridge.BEARER_FILTER_ORDER,
                "replacesExistingAuth", false));
    }
}
