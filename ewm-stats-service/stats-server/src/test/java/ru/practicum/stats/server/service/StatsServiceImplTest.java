package ru.practicum.stats.server.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import ru.practicum.stats.dto.EndpointHitDto;
import ru.practicum.stats.dto.ViewStatsDto;
import ru.practicum.stats.server.exception.ValidationException;
import ru.practicum.stats.server.model.EndpointHit;
import ru.practicum.stats.server.repository.StatsRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционные тесты для {@link StatsServiceImpl}.
 * Используется встроенная база данных H2 для изоляции и скорости.
 * Тесты проверяют:
 * - сохранение хитов,
 * - получение статистики (с фильтрацией по времени, URI и уникальности),
 * - валидацию входных данных.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "logging.level.ru.practicum.stats=DEBUG"
})
class StatsServiceImplTest {

    @Autowired
    private StatsService statsService;

    @Autowired
    private StatsRepository statsRepository;

    @BeforeEach
    void setUp() {
        // Очистка не обязательна при ddl-auto=create-drop, но для надёжности:
        statsRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        statsRepository.deleteAll();
    }

    // ==================== ТЕСТЫ ДЛЯ saveHit ====================

    @Test
    void saveHit_validDto_savesToDatabase() {
        EndpointHitDto dto = new EndpointHitDto(
                null,
                "test-app",
                "/events/1",
                "192.168.1.1",
                "2025-11-23 10:00:00"
        );

        statsService.saveHit(dto);

        List<EndpointHit> hits = statsRepository.findAll();
        assertThat(hits).hasSize(1);
        EndpointHit hit = hits.getFirst();
        assertThat(hit.getApp()).isEqualTo("test-app");
        assertThat(hit.getUri()).isEqualTo("/events/1");
        assertThat(hit.getIp()).isEqualTo("192.168.1.1");
        assertThat(hit.getTimestamp()).isEqualTo(LocalDateTime.of(2025, 11, 23, 10, 0, 0));
    }

    // ==================== ТЕСТЫ ДЛЯ getStats ====================

    @Test
    void getStats_noUrisAndNotUnique_returnsAllHitsInPeriod() {

        saveHit("app1", "/u1", "1.1.1.1", "2025-11-23 10:00:00"); // вне диапазона
        saveHit("app1", "/u1", "1.1.1.1", "2025-11-23 11:00:00"); // в диапазоне
        saveHit("app1", "/u2", "2.2.2.2", "2025-11-23 12:00:00"); // в диапазоне
        saveHit("app1", "/u1", "1.1.1.1", "2025-11-23 13:00:00"); // вне диапазона


        List<ViewStatsDto> result = statsService.getStats(
                urlEncode("2025-11-23 10:30:00"),
                urlEncode("2025-11-23 12:30:00"),
                null,
                false
        );


        assertThat(result).hasSize(2);
        assertThat(result)
                .extracting(ViewStatsDto::getUri)
                .containsExactlyInAnyOrder("/u1", "/u2");

        assertThat(result)
                .extracting(ViewStatsDto::getHits)
                .containsExactlyInAnyOrder(1L, 1L); // каждый URI — 1 хит
    }

    @Test
    void getStats_withUris_filtersByUris() {

        saveHit("app1", "/u1", "1.1.1.1", "2025-11-23 11:00:00");
        saveHit("app1", "/u2", "2.2.2.2", "2025-11-23 11:00:00");
        saveHit("app1", "/u3", "3.3.3.3", "2025-11-23 11:00:00");

        List<ViewStatsDto> result = statsService.getStats(
                urlEncode("2025-11-23 10:00:00"),
                urlEncode("2025-11-23 12:00:00"),
                List.of("/u1", "/u2"),
                false
        );

        assertThat(result).hasSize(2);
        assertThat(result)
                .extracting(ViewStatsDto::getUri)
                .containsExactlyInAnyOrder("/u1", "/u2");
    }

    @Test
    void getStats_uniqueCounts_onlyOneHitPerIp() {

        saveHit("app1", "/u1", "1.1.1.1", "2025-11-23 11:00:00");
        saveHit("app1", "/u1", "1.1.1.1", "2025-11-23 11:01:00"); // тот же IP — не считается
        saveHit("app1", "/u1", "2.2.2.2", "2025-11-23 11:02:00"); // другой IP — считается

        List<ViewStatsDto> result = statsService.getStats(
                urlEncode("2025-11-23 10:00:00"),
                urlEncode("2025-11-23 12:00:00"),
                List.of("/u1"),
                true
        );

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getHits()).isEqualTo(2L); // два уникальных IP
    }

    @Test
    void getStats_noUrisAndUnique_returnsUniqueHitsForAllUris() {

        // Хиты для URI /u1
        saveHit("app1", "/u1", "1.1.1.1", "2025-11-23 11:00:00"); // IP1 → уникальный
        saveHit("app1", "/u1", "1.1.1.1", "2025-11-23 11:01:00"); // IP1 → дубль (не считается)
        saveHit("app1", "/u1", "2.2.2.2", "2025-11-23 11:02:00"); // IP2 → уникальный

        // Хиты для URI /u2
        saveHit("app1", "/u2", "1.1.1.1", "2025-11-23 11:03:00"); // IP1 → уникальный для /u2
        saveHit("app1", "/u2", "3.3.3.3", "2025-11-23 11:04:00"); // IP3 → уникальный для /u2
        saveHit("app1", "/u2", "3.3.3.3", "2025-11-23 11:05:00"); // IP3 → дубль

        List<ViewStatsDto> result = statsService.getStats(
                urlEncode("2025-11-23 10:00:00"),
                urlEncode("2025-11-23 12:00:00"),
                null,  // ← URI не указаны → статистика по всем URI
                true   // ← уникальные IP
        );

        assertThat(result).hasSize(2);

        // Проверяем статистику для /u1
        ViewStatsDto u1Stats = result.stream()
                .filter(s -> "/u1".equals(s.getUri()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Статистика для /u1 не найдена"));
        assertThat(u1Stats.getHits()).isEqualTo(2L); // два уникальных IP: 1.1.1.1 и 2.2.2.2

        // Проверяем статистику для /u2
        ViewStatsDto u2Stats = result.stream()
                .filter(s -> "/u2".equals(s.getUri()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Статистика для /u2 не найдена"));
        assertThat(u2Stats.getHits()).isEqualTo(2L); // два уникальных IP: 1.1.1.1 и 3.3.3.3
    }

    @Test
    void getStats_startAfterEnd_throwsIllegalArgumentException() {

        assertThatThrownBy(() ->
                statsService.getStats(
                        urlEncode("2025-11-23 12:00:00"),
                        urlEncode("2025-11-23 10:00:00"),
                        null,
                        false
                )
        ).isInstanceOf(ValidationException.class)
                .hasMessage("Дата начала не может быть позже даты окончания");
    }

    @Test
    void getStats_invalidDateFormat_throwsIllegalArgumentException() {

        assertThatThrownBy(() ->
                statsService.getStats("invalid-date", "2025-11-23 10:00:00", null, false)
        ).isInstanceOf(ValidationException.class)
                .hasMessageStartingWith("Некорректный формат даты для параметра:");
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ====================

    private void saveHit(String app, String uri, String ip, String timestamp) {
        EndpointHitDto dto = new EndpointHitDto(null, app, uri, ip, timestamp);
        statsService.saveHit(dto);
    }

    private String urlEncode(String value) {
        return value.replace(" ", "%20");
    }
}