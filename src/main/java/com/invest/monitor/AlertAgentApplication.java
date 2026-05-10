package com.invest.monitor;

import com.invest.monitor.config.MonitorConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Точка входа Investment Monitor Agent. */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(MonitorConfig.class)
public class AlertAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlertAgentApplication.class, args);
    }
}
