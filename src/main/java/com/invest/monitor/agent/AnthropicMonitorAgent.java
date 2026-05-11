package com.invest.monitor.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.invest.monitor.config.MonitorConfig;
import com.invest.monitor.domain.Trigger;
import com.invest.monitor.domain.TriggerFrequency;
import com.invest.monitor.state.TriggerStateService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Агент на базе Anthropic Claude с tool-use (Tavily через {@link AgentTools}).
 * Активен при {@code monitor.agent-provider=anthropic} (значение по умолчанию).
 */
@Service
@ConditionalOnProperty(name = "monitor.agent-provider", havingValue = "anthropic", matchIfMissing = true)
public class AnthropicMonitorAgent extends AbstractMonitorAgent {

    private final ChatClient chatClient;
    private final AgentTools agentTools;

    public AnthropicMonitorAgent(ChatClient.Builder builder,
                                  AgentTools agentTools,
                                  TriggerStateService stateService,
                                  ObjectMapper mapper,
                                  MonitorConfig config) {
        super(stateService, mapper, config);
        this.chatClient = builder.build();
        this.agentTools = agentTools;
    }

    @Override
    protected String callProvider(String isin, List<Trigger> triggers, TriggerFrequency frequency) {
        return chatClient.prompt()
                .system(systemPrompt)
                .user(buildUserMessage(isin, triggers, frequency))
                .tools(agentTools)
                .call()
                .content();
    }
}
