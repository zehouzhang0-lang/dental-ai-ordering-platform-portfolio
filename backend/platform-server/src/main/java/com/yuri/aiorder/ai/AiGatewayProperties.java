package com.yuri.aiorder.ai;

import com.yuri.aiorder.common.UserRole;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai")
public class AiGatewayProperties {

    public static final String AI_LANGCHAIN_ENABLED_ENV = "AI_LANGCHAIN_ENABLED";

    private String provider = "deterministic";
    private int maxRequestsPerUserHour = 120;
    private int maxModelRetries = 1;
    private long inputTokenCostMicrousd = 0;
    private long outputTokenCostMicrousd = 0;
    private long dailyBudgetMicrousd = 0;
    private long adminDailyBudgetMicrousd = 0;
    private long csDailyBudgetMicrousd = 0;
    private long doctorDailyBudgetMicrousd = 0;
    private long workerDailyBudgetMicrousd = 0;
    private boolean budgetNotificationEnabled = true;
    private boolean budgetCircuitBreakerEnabled = false;
    private final DeepSeek deepseek = new DeepSeek();
    private final LangChain langchain = new LangChain();
    private final ExternalAlert externalAlert = new ExternalAlert();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public int getMaxRequestsPerUserHour() {
        return maxRequestsPerUserHour;
    }

    public void setMaxRequestsPerUserHour(int maxRequestsPerUserHour) {
        this.maxRequestsPerUserHour = maxRequestsPerUserHour;
    }

    public int getMaxModelRetries() {
        return maxModelRetries;
    }

    public void setMaxModelRetries(int maxModelRetries) {
        this.maxModelRetries = maxModelRetries;
    }

    public long getInputTokenCostMicrousd() {
        return inputTokenCostMicrousd;
    }

    public void setInputTokenCostMicrousd(long inputTokenCostMicrousd) {
        this.inputTokenCostMicrousd = inputTokenCostMicrousd;
    }

    public long getOutputTokenCostMicrousd() {
        return outputTokenCostMicrousd;
    }

    public void setOutputTokenCostMicrousd(long outputTokenCostMicrousd) {
        this.outputTokenCostMicrousd = outputTokenCostMicrousd;
    }

    public long getDailyBudgetMicrousd() {
        return dailyBudgetMicrousd;
    }

    public void setDailyBudgetMicrousd(long dailyBudgetMicrousd) {
        this.dailyBudgetMicrousd = dailyBudgetMicrousd;
    }

    public long getCsDailyBudgetMicrousd() {
        return csDailyBudgetMicrousd;
    }

    public void setCsDailyBudgetMicrousd(long csDailyBudgetMicrousd) {
        this.csDailyBudgetMicrousd = csDailyBudgetMicrousd;
    }

    public long getAdminDailyBudgetMicrousd() {
        return adminDailyBudgetMicrousd;
    }

    public void setAdminDailyBudgetMicrousd(long adminDailyBudgetMicrousd) {
        this.adminDailyBudgetMicrousd = adminDailyBudgetMicrousd;
    }

    public long getDoctorDailyBudgetMicrousd() {
        return doctorDailyBudgetMicrousd;
    }

    public void setDoctorDailyBudgetMicrousd(long doctorDailyBudgetMicrousd) {
        this.doctorDailyBudgetMicrousd = doctorDailyBudgetMicrousd;
    }

    public long getWorkerDailyBudgetMicrousd() {
        return workerDailyBudgetMicrousd;
    }

    public void setWorkerDailyBudgetMicrousd(long workerDailyBudgetMicrousd) {
        this.workerDailyBudgetMicrousd = workerDailyBudgetMicrousd;
    }

    public long dailyBudgetMicrousdForRole(UserRole role) {
        if (role == null) {
            return 0;
        }
        return switch (role) {
            case ADMIN -> adminDailyBudgetMicrousd;
            case CS -> csDailyBudgetMicrousd;
            case DOCTOR -> doctorDailyBudgetMicrousd;
            case WORKER -> workerDailyBudgetMicrousd;
        };
    }

    public boolean isBudgetNotificationEnabled() {
        return budgetNotificationEnabled;
    }

    public void setBudgetNotificationEnabled(boolean budgetNotificationEnabled) {
        this.budgetNotificationEnabled = budgetNotificationEnabled;
    }

    public boolean isBudgetCircuitBreakerEnabled() {
        return budgetCircuitBreakerEnabled;
    }

    public void setBudgetCircuitBreakerEnabled(boolean budgetCircuitBreakerEnabled) {
        this.budgetCircuitBreakerEnabled = budgetCircuitBreakerEnabled;
    }

    public DeepSeek getDeepseek() {
        return deepseek;
    }

    public LangChain getLangchain() {
        return langchain;
    }

    public ExternalAlert getExternalAlert() {
        return externalAlert;
    }

    public boolean deepSeekEnabled() {
        return "deepseek".equalsIgnoreCase(provider)
                && deepseek.isEnabled()
                && deepseek.getApiKey() != null
                && !deepseek.getApiKey().isBlank()
                && !deepseek.getApiKey().startsWith("replace-with");
    }

    public boolean langChainDeepSeekEnabled() {
        return "langchain-deepseek".equalsIgnoreCase(provider)
                && "deepseek".equalsIgnoreCase(langchain.getProvider())
                && langchain.isEnabled()
                && deepseek.isEnabled()
                && deepseek.getApiKey() != null
                && !deepseek.getApiKey().isBlank()
                && !deepseek.getApiKey().startsWith("replace-with");
    }

    public static class DeepSeek {
        private boolean enabled = false;
        private String baseUrl = "https://api.deepseek.com";
        private String apiKey = "";
        private String model = "deepseek-chat";
        private long dailyBudgetMicrousd = 0;
        private double temperature = 0.2;
        private int maxTokens = 800;
        private int connectTimeoutSeconds = 10;
        private int readTimeoutSeconds = 45;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public long getDailyBudgetMicrousd() {
            return dailyBudgetMicrousd;
        }

        public void setDailyBudgetMicrousd(long dailyBudgetMicrousd) {
            this.dailyBudgetMicrousd = dailyBudgetMicrousd;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public int getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }

        public int getReadTimeoutSeconds() {
            return readTimeoutSeconds;
        }

        public void setReadTimeoutSeconds(int readTimeoutSeconds) {
            this.readTimeoutSeconds = readTimeoutSeconds;
        }
    }

    public static class LangChain {
        private boolean enabled = false;
        private String provider = "deepseek";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }
    }

    public static class ExternalAlert {
        private boolean webhookEnabled = false;
        private String webhookUrl = "";
        private int connectTimeoutSeconds = 5;
        private int readTimeoutSeconds = 10;
        private boolean schedulerEnabled = false;
        private int schedulerBatchSize = 50;
        private long schedulerFixedDelayMillis = 60000;
        private long schedulerInitialDelayMillis = 60000;
        private int maxAttempts = 3;
        private boolean webhookSigningEnabled = false;
        private String webhookSigningSecret = "";
        private boolean receiverVerificationEnabled = false;
        private String receiverSigningSecret = "";
        private int receiverReplayWindowSeconds = 300;

        public boolean isWebhookEnabled() {
            return webhookEnabled;
        }

        public void setWebhookEnabled(boolean webhookEnabled) {
            this.webhookEnabled = webhookEnabled;
        }

        public String getWebhookUrl() {
            return webhookUrl;
        }

        public void setWebhookUrl(String webhookUrl) {
            this.webhookUrl = webhookUrl;
        }

        public int getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }

        public int getReadTimeoutSeconds() {
            return readTimeoutSeconds;
        }

        public void setReadTimeoutSeconds(int readTimeoutSeconds) {
            this.readTimeoutSeconds = readTimeoutSeconds;
        }

        public boolean isSchedulerEnabled() {
            return schedulerEnabled;
        }

        public void setSchedulerEnabled(boolean schedulerEnabled) {
            this.schedulerEnabled = schedulerEnabled;
        }

        public int getSchedulerBatchSize() {
            return schedulerBatchSize;
        }

        public void setSchedulerBatchSize(int schedulerBatchSize) {
            this.schedulerBatchSize = schedulerBatchSize;
        }

        public long getSchedulerFixedDelayMillis() {
            return schedulerFixedDelayMillis;
        }

        public void setSchedulerFixedDelayMillis(long schedulerFixedDelayMillis) {
            this.schedulerFixedDelayMillis = schedulerFixedDelayMillis;
        }

        public long getSchedulerInitialDelayMillis() {
            return schedulerInitialDelayMillis;
        }

        public void setSchedulerInitialDelayMillis(long schedulerInitialDelayMillis) {
            this.schedulerInitialDelayMillis = schedulerInitialDelayMillis;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public boolean isWebhookSigningEnabled() {
            return webhookSigningEnabled;
        }

        public void setWebhookSigningEnabled(boolean webhookSigningEnabled) {
            this.webhookSigningEnabled = webhookSigningEnabled;
        }

        public String getWebhookSigningSecret() {
            return webhookSigningSecret;
        }

        public void setWebhookSigningSecret(String webhookSigningSecret) {
            this.webhookSigningSecret = webhookSigningSecret;
        }

        public boolean isReceiverVerificationEnabled() {
            return receiverVerificationEnabled;
        }

        public void setReceiverVerificationEnabled(boolean receiverVerificationEnabled) {
            this.receiverVerificationEnabled = receiverVerificationEnabled;
        }

        public String getReceiverSigningSecret() {
            return receiverSigningSecret;
        }

        public void setReceiverSigningSecret(String receiverSigningSecret) {
            this.receiverSigningSecret = receiverSigningSecret;
        }

        public int getReceiverReplayWindowSeconds() {
            return receiverReplayWindowSeconds;
        }

        public void setReceiverReplayWindowSeconds(int receiverReplayWindowSeconds) {
            this.receiverReplayWindowSeconds = receiverReplayWindowSeconds;
        }
    }
}
