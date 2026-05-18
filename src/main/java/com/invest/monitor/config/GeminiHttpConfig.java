package com.invest.monitor.config;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@ConditionalOnProperty(name = "monitor.agent-provider", havingValue = "gemini")
public class GeminiHttpConfig {

    @Bean("geminiRestClient")
    public RestClient geminiRestClient(MonitorConfig config) {
        MonitorConfig.Google.Http http = config.google().http();

        ConnectionConfig connConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(http.connectTimeoutMs()))
                .setSocketTimeout(Timeout.ofSeconds(http.responseTimeoutSec()))
                .setTimeToLive(TimeValue.ofSeconds(http.connectionTtlSec()))
                .build();

        HttpClientConnectionManager cm = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(connConfig)
                .setMaxConnTotal(http.maxConnections())
                .setMaxConnPerRoute(http.maxConnections())
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(cm)
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(http.maxIdleTimeSec()))
                .build();

        return RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .baseUrl(config.google().baseUrl())
                .build();
    }
}
