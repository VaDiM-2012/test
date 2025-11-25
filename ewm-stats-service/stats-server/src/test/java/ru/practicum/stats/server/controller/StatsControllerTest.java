package ru.practicum.stats.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import ru.practicum.stats.dto.EndpointHitDto;
import ru.practicum.stats.dto.ViewStatsDto;
import ru.practicum.stats.server.exception.ValidationException;
import ru.practicum.stats.server.service.StatsService;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StatsController.class)
@TestPropertySource(properties =
        "logging.level.ru.practicum.stats=DEBUG"
)
class StatsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StatsService statsService;

    @Autowired
    private final ObjectMapper objectMapper;

    public StatsControllerTest() {
        // Регистрируем модуль для работы с LocalDateTime в JSON
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    // ==================== ТЕСТЫ ДЛЯ /hit ====================

    @Test
    void hit_validDto_savesHitAndReturns201() throws Exception {

        EndpointHitDto dto = new EndpointHitDto(
                null,
                "test-app",
                "/events/1",
                "192.168.1.1",
                "2025-11-23 10:00:00"
        );

        String json = objectMapper.writeValueAsString(dto);


        mockMvc.perform(post("/hit")
                        .content(json)
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                )
                .andExpect(status().isCreated())
                .andExpect(content().string(""));


        verify(statsService).saveHit(argThat(hit ->
                "test-app".equals(hit.getApp()) &&
                        "/events/1".equals(hit.getUri()) &&
                        "192.168.1.1".equals(hit.getIp()) &&
                        "2025-11-23 10:00:00".equals(hit.getTimestamp())
        ));
    }

    @Test
    void hit_invalidJson_returns400() throws Exception {
        mockMvc.perform(post("/hit")
                        .content("{\"app\": \"test\"") // некорректный JSON
                        .contentType(MediaType.APPLICATION_JSON)
                )
                .andExpect(status().isBadRequest());
    }

    // ==================== ТЕСТЫ ДЛЯ /stats ====================

    @Test
    void getStats_validParams_returnsStats() throws Exception {

        List<ViewStatsDto> mockStats = List.of(
                new ViewStatsDto("app1", "/u1", 5L),
                new ViewStatsDto("app1", "/u2", 3L)
        );
        when(statsService.getStats(anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(mockStats);

        mockMvc.perform(get("/stats")
                        .param("start", "2025-11-23 10:00:00")
                        .param("end", "2025-11-23 12:00:00")
                        .param("unique", "false")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size()").value(2))
                .andExpect(jsonPath("$[0].app").value("app1"))
                .andExpect(jsonPath("$[0].uri").value("/u1"))
                .andExpect(jsonPath("$[0].hits").value(5))
                .andExpect(jsonPath("$[1].uri").value("/u2"));

        verify(statsService).getStats(
                eq("2025-11-23 10:00:00"),
                eq("2025-11-23 12:00:00"),
                isNull(),
                eq(false)
        );
    }

    @Test
    void getStats_withUris_filtersCorrectly() throws Exception {
        when(statsService.getStats(anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(List.of(new ViewStatsDto("app1", "/u1", 2L)));

        mockMvc.perform(get("/stats")
                        .param("start", "2025-11-23 10:00:00")
                        .param("end", "2025-11-23 12:00:00")
                        .param("uris", "/u1")
                        .param("uris", "/u2")
                        .param("unique", "true")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size()").value(1));

        verify(statsService).getStats(
                eq("2025-11-23 10:00:00"),
                eq("2025-11-23 12:00:00"),
                argThat(uris -> uris != null && uris.contains("/u1") && uris.contains("/u2")),
                eq(true)
        );
    }

    @Test
    void getStats_missingStartParam_returns400() throws Exception {
        mockMvc.perform(get("/stats")
                        .param("end", "2025-11-23 12:00:00")
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void getStats_missingEndParam_returns400() throws Exception {
        mockMvc.perform(get("/stats")
                        .param("start", "2025-11-23 10:00:00")
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void getStats_serviceThrowsValidationException_returns400() throws Exception {
        doThrow(new ValidationException("Дата начала не может быть позже даты окончания"))
                .when(statsService).getStats(anyString(), anyString(), any(), anyBoolean());

        mockMvc.perform(get("/stats")
                        .param("start", "2025-11-23 12:00:00")
                        .param("end", "2025-11-23 10:00:00")
                )
                .andExpect(status().isBadRequest());
    }
}