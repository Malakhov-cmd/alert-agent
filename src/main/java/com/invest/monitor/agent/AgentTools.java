package com.invest.monitor.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.invest.monitor.config.MonitorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Инструменты агента, доступные Claude через tool-use.
 * Spring AI регистрирует методы с @Tool как вызываемые функции.
 */
@Component
public class AgentTools {

    private static final Logger log = LoggerFactory.getLogger(AgentTools.class);

    private final String       tavilyApiKey;
    private final int          maxResults;
    private final List<String> includeDomains;
    private final RestClient   restClient;

    public AgentTools(MonitorConfig config, RestClient.Builder builder) {
        MonitorConfig.Search search = config.search();
        this.tavilyApiKey   = search.tavilyApiKey();
        this.maxResults     = search.maxResults();
        this.includeDomains = search.includeDomains();
        this.restClient     = builder.baseUrl(search.tavilyUrl()).build();
    }

    // ── Tools ────────────────────────────────────────────────────────

    /**
     * Поиск актуальной информации в интернете.
     * Claude вызывает этот инструмент когда нужны свежие рыночные данные,
     * новости по эмитенту или котировки облигаций.
     *
     * @param query поисковый запрос на русском или английском
     * @return форматированный текст с результатами поиска
     */
    @Tool(description = """
            Поиск актуальной информации в интернете по российским облигациям и финансовым новостям.
            Используй для: котировок YTM, новостей эмитента, изменений кредитного рейтинга,
            данных о выплатах купонов и погашениях, финансовой отчётности.
            """)
    public String webSearch(String query) {
        log.debug("web_search: «{}»", query);

        Map<String, Object> body = Map.of(
                "api_key",         tavilyApiKey,
                "query",           query,
                "max_results",     maxResults,
                "search_depth",    "advanced",
                "include_domains", includeDomains
        );

        try {
            TavilyResponse response = restClient
                    .post()
                    .uri("/search")
                    .body(body)
                    .retrieve()
                    .body(TavilyResponse.class);

            if (response == null || response.results() == null || response.results().isEmpty()) {
                return "Результаты не найдены для запроса: " + query;
            }

            return formatResults(query, response.results());

        } catch (RestClientException e) {
            log.error("Ошибка Tavily API: {}", e.getMessage(), e);
            return "Ошибка поиска: " + e.getMessage();
        }
    }

    // ── Форматирование ───────────────────────────────────────────────

    private String formatResults(String query, List<TavilyResult> results) {
        String body = results.stream()
                .map(r -> """
                        **%s**
                        %s
                        Источник: %s
                        """.formatted(r.title(), r.content(), r.url()))
                .collect(Collectors.joining("\n---\n"));

        return "Результаты поиска по запросу «%s»:\n\n%s".formatted(query, body);
    }

    // ── Tavily API DTO ───────────────────────────────────────────────

    record TavilyResponse(
            String             query,
            List<TavilyResult> results
    ) {}

    record TavilyResult(
            String title,
            String url,
            String content,
            double score,
            @JsonProperty("published_date") String publishedDate
    ) {}
}
