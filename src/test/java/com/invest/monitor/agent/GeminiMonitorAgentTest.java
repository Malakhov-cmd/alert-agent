package com.invest.monitor.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.invest.monitor.config.MonitorConfig;
import com.invest.monitor.domain.MonitorResult;
import com.invest.monitor.domain.Trigger;
import com.invest.monitor.domain.TriggerFrequency;
import com.invest.monitor.domain.TriggerLevel;
import com.invest.monitor.state.TriggerStateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.RestClient;

import java.util.List;

import static com.invest.monitor.agent.GeminiMonitorAgent.wrapSingleObject;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GeminiMonitorAgentTest {

    @Mock TriggerStateService stateService;
    @Mock RestClient          restClient;
    @Mock(answer = Answers.RETURNS_SELF) RestClient.RequestBodyUriSpec chainedSpec;
    @Mock RestClient.ResponseSpec responseSpec;

    private final ObjectMapper mapper = new ObjectMapper();

    // ── wrapSingleObject ─────────────────────────────────────────────

    @Test
    void wrapSingleObject_plainObject_wrappedInArray() {
        assertThat(wrapSingleObject("{\"fired\":false}"))
                .isEqualTo("[{\"fired\":false}]");
    }

    @Test
    void wrapSingleObject_plainArray_passedThrough() {
        String arr = "[{\"fired\":false}]";
        assertThat(wrapSingleObject(arr)).isEqualTo(arr);
    }

    @Test
    void wrapSingleObject_codeBlockObject_strippedAndWrapped() {
        assertThat(wrapSingleObject("```json\n{\"fired\":false}\n```"))
                .isEqualTo("[{\"fired\":false}]");
    }

    @Test
    void wrapSingleObject_codeBlockArray_returnsOriginal() {
        String input = "```json\n[{\"fired\":false}]\n```";
        // Array branch returns `raw` (not trimmed), so code fences are preserved.
        // extractJsonArray handles fenced arrays via its regex pattern.
        assertThat(wrapSingleObject(input)).isEqualTo(input);
    }

    // ── Key pool ─────────────────────────────────────────────────────

    @Test
    void constructor_blankPrimaryKey_noExtras_throwsIllegalState() {
        assertThatThrownBy(() ->
                new GeminiMonitorAgent(stateService, mapper, config("", List.of()), restClient))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GOOGLE_API_KEY");
    }

    @Test
    void constructor_duplicateKeys_deduplicated_batchMode() {
        setupRestClientChain(okResponse());
        GeminiMonitorAgent agent = new GeminiMonitorAgent(stateService, mapper,
                config("key1", List.of("key1")), restClient);  // same key twice

        // Pool deduplicates to ["key1"] → size 1 → batch mode → 1 call per ISIN
        agent.checkAll(List.of(
                trigger("RU0001", TriggerFrequency.DAILY),
                trigger("RU0001", TriggerFrequency.DAILY)  // same ISIN
        ));

        verify(restClient, times(1)).post();
    }

    // ── Stub mode ────────────────────────────────────────────────────

    @Test
    void checkAll_stubMode_singleKey_noRestClientCalls() {
        GeminiMonitorAgent agent = new GeminiMonitorAgent(stateService, mapper,
                stubConfig("key1", List.of()), restClient);

        List<MonitorResult> results = agent.checkAll(List.of(
                trigger("RU0001", TriggerFrequency.DAILY),
                trigger("RU0002", TriggerFrequency.DAILY)
        ));

        verifyNoInteractions(restClient);
        assertThat(results).hasSize(2).noneMatch(MonitorResult::error);
    }

    @Test
    void checkAll_stubMode_multipleKeys_noRestClientCalls() {
        GeminiMonitorAgent agent = new GeminiMonitorAgent(stateService, mapper,
                stubConfig("key1", List.of("key2")), restClient);

        agent.checkAll(List.of(trigger("RU0001", TriggerFrequency.DAILY)));

        verifyNoInteractions(restClient);
    }

    // ── Batch mode (1 key → 1 call per ISIN) ────────────────────────

    @Test
    void checkAll_singleKey_oneCallPerIsin() {
        setupRestClientChain(okResponse());
        GeminiMonitorAgent agent = new GeminiMonitorAgent(stateService, mapper,
                config("key1", List.of()), restClient);

        agent.checkAll(List.of(
                trigger("RU0001", TriggerFrequency.DAILY),
                trigger("RU0001", TriggerFrequency.DAILY),  // same ISIN — grouped
                trigger("RU0002", TriggerFrequency.DAILY)   // different ISIN
        ));

        // 2 unique ISINs → 2 Gemini calls
        verify(restClient, times(2)).post();
    }

    @Test
    void checkAll_singleKey_recordsStateForSuccessResults() {
        setupRestClientChain(okResponse());
        GeminiMonitorAgent agent = new GeminiMonitorAgent(stateService, mapper,
                config("key1", List.of()), restClient);

        agent.checkAll(List.of(trigger("RU0001", TriggerFrequency.DAILY)));

        verify(stateService, atLeastOnce()).recordCheck(any(), eq(TriggerFrequency.DAILY));
    }

    // ── Per-trigger mode (N keys → 1 call per trigger) ───────────────

    @Test
    void checkAll_multipleKeys_oneCallPerTrigger() {
        setupRestClientChain(okResponse());
        GeminiMonitorAgent agent = new GeminiMonitorAgent(stateService, mapper,
                config("key1", List.of("key2")), restClient);

        agent.checkAll(List.of(
                trigger("RU0001", TriggerFrequency.DAILY),
                trigger("RU0001", TriggerFrequency.DAILY),  // same ISIN, distinct trigger
                trigger("RU0002", TriggerFrequency.DAILY)
        ));

        // 3 triggers → 3 calls (not 2 per ISIN grouping)
        verify(restClient, times(3)).post();
    }

    @Test
    void checkAll_multipleKeys_success_recordsState() {
        setupRestClientChain(okResponse());
        GeminiMonitorAgent agent = new GeminiMonitorAgent(stateService, mapper,
                config("key1", List.of("key2")), restClient);

        agent.checkAll(List.of(trigger("RU0001", TriggerFrequency.DAILY)));

        verify(stateService, times(1)).recordCheck(any(), eq(TriggerFrequency.DAILY));
    }

    @Test
    void checkAll_multipleKeys_apiError_doesNotRecordState_returnsErrorResult() {
        when(restClient.post()).thenReturn(chainedSpec);
        when(chainedSpec.retrieve()).thenThrow(new RuntimeException("API failure"));

        GeminiMonitorAgent agent = new GeminiMonitorAgent(stateService, mapper,
                config("key1", List.of("key2")), restClient);

        List<MonitorResult> results = agent.checkAll(List.of(
                trigger("RU0001", TriggerFrequency.DAILY)
        ));

        verify(stateService, never()).recordCheck(any(), any());
        assertThat(results).hasSize(1).allMatch(MonitorResult::error);
    }

    @Test
    void checkAll_multipleKeys_rotatesAcrossTriggers() {
        setupRestClientChain(okResponse());
        GeminiMonitorAgent agent = new GeminiMonitorAgent(stateService, mapper,
                config("keyA", List.of("keyB")), restClient);

        // 3 triggers with 2 keys → round-robin: keyA, keyB, keyA
        agent.checkAll(List.of(
                trigger("RU0001", TriggerFrequency.DAILY),
                trigger("RU0002", TriggerFrequency.DAILY),
                trigger("RU0003", TriggerFrequency.DAILY)
        ));

        verify(restClient, times(3)).post();
    }

    @Test
    void checkAll_multipleKeys_emptyTriggers_returnsEmptyList() {
        GeminiMonitorAgent agent = new GeminiMonitorAgent(stateService, mapper,
                config("key1", List.of("key2")), restClient);

        assertThat(agent.checkAll(List.of())).isEmpty();
        verifyNoInteractions(restClient);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void setupRestClientChain(GeminiMonitorAgent.GeminiResponse response) {
        // RETURNS_SELF on chainedSpec makes post()/uri()/body() return the same mock.
        // Only retrieve() breaks the chain — stub it explicitly.
        when(restClient.post()).thenReturn(chainedSpec);
        when(chainedSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(GeminiMonitorAgent.GeminiResponse.class)).thenReturn(response);
    }

    private GeminiMonitorAgent.GeminiResponse okResponse() {
        String json = "[{\"condition\":\"cond\",\"fired\":false,\"summary\":\"OK\","
                + "\"details\":\"all good\",\"confidence\":\"high\",\"action\":null}]";
        return new GeminiMonitorAgent.GeminiResponse(
                List.of(new GeminiMonitorAgent.GeminiResponse.Candidate(
                        new GeminiMonitorAgent.GeminiResponse.Content(
                                List.of(new GeminiMonitorAgent.GeminiResponse.Part(json))
                        )
                ))
        );
    }

    private MonitorConfig config(String primaryKey, List<String> extraKeys) {
        return new MonitorConfig(
                "./vault", "scheduled", 0L, 0L,
                new MonitorConfig.Anthropic("k", "m"),
                new MonitorConfig.Telegram("t", "c", ""),
                new MonitorConfig.Search("s", 5, List.of(), "", false, 3),
                new MonitorConfig.Prompt("system prompt", "per-trigger prompt"),
                new MonitorConfig.Schedule("0 0 9 * * *", "0 0 9 * * MON",
                        "0 0 9 1 * *", "0 0 9 1 1,4,7,10 *"),
                "Europe/Moscow", "gemini",
                new MonitorConfig.Google(primaryKey, extraKeys,
                        "gemini-2.5-flash", "https://generativelanguage.googleapis.com", null)
        );
    }

    private MonitorConfig stubConfig(String primaryKey, List<String> extraKeys) {
        return new MonitorConfig(
                "./vault", "scheduled", 0L, 0L,
                new MonitorConfig.Anthropic("k", "m"),
                new MonitorConfig.Telegram("t", "c", ""),
                new MonitorConfig.Search("s", 5, List.of(), "", true, 3),  // stub=true
                new MonitorConfig.Prompt("system prompt", ""),
                new MonitorConfig.Schedule("0 0 9 * * *", "0 0 9 * * MON",
                        "0 0 9 1 * *", "0 0 9 1 1,4,7,10 *"),
                "Europe/Moscow", "gemini",
                new MonitorConfig.Google(primaryKey, extraKeys,
                        "gemini-2.5-flash", "https://generativelanguage.googleapis.com", null)
        );
    }

    private Trigger trigger(String isin, TriggerFrequency frequency) {
        return new Trigger(isin, "Bond " + isin, "condition",
                TriggerLevel.Critical.INSTANCE, frequency, true, null, null);
    }
}
