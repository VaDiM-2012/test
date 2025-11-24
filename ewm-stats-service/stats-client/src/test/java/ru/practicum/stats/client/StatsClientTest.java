package ru.practicum.stats.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;
import ru.practicum.stats.dto.EndpointHitDto;
import ru.practicum.stats.dto.ViewStatsDto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Модульные тесты для StatsClient.
 * Используем Mockito для имитации RestTemplate и RestTemplateBuilder.
 * Тесты написаны с использованием JUnit 5.
 */
@ExtendWith(MockitoExtension.class)
class StatsClientTest {

    // Форматтер для проверки временных меток в DTO и параметрах запроса
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String SERVER_URL = "http://localhost:9090";

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private RestTemplateBuilder restTemplateBuilder;

    @Captor
    private ArgumentCaptor<HttpEntity<EndpointHitDto>> requestEntityCaptor;

    @Captor
    private ArgumentCaptor<String> urlTemplateCaptor;

    @Captor
    private ArgumentCaptor<Map<String, ?>> uriVariablesCaptor;

    private StatsClient statsClient;

    /**
     * Настройка перед каждым тестом.
     * Имитируем поведение RestTemplateBuilder
     * который фактически используется внутри StatsClient.
     */
    @BeforeEach
    void setUp() {
        // Имитируем конфигурацию RestTemplateBuilder
        when(restTemplateBuilder.uriTemplateHandler(any(DefaultUriBuilderFactory.class))).thenReturn(restTemplateBuilder);
        when(restTemplateBuilder.requestFactory(any(Class.class))).thenReturn(restTemplateBuilder);
        when(restTemplateBuilder.build()).thenReturn(restTemplate);

        // Создаем тестируемый объект, используя мок-зависимости
        statsClient = new StatsClient(SERVER_URL, restTemplateBuilder);

        // Проверяем, что клиент был инициализирован корректно
        verify(restTemplateBuilder).uriTemplateHandler(any(DefaultUriBuilderFactory.class));
        verify(restTemplateBuilder).build();
    }

    // --- Тесты для метода hit ---

    @Test
    void hit_Success() {
        // Подготовка данных
        String app = "ewm-main-service";
        String uri = "/events/1";
        String ip = "192.168.0.1";
        LocalDateTime now = LocalDateTime.now();
        String timestampFormatted = now.format(FORMATTER);

        // Имитация успешного ответа сервера (201 Created или 200 OK, но postForEntity в Void)
        when(restTemplate.postForEntity(eq("/hit"), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(new ResponseEntity<>(HttpStatus.CREATED));

        // Вызов метода
        statsClient.hit(app, uri, ip, now);

        // Проверка: был ли вызван postForEntity с правильными параметрами
        verify(restTemplate).postForEntity(eq("/hit"), requestEntityCaptor.capture(), eq(Void.class));

        // Проверка содержимого отправленного DTO
        EndpointHitDto sentDto = requestEntityCaptor.getValue().getBody();
        assertNotNull(sentDto);
        assertEquals(app, sentDto.getApp());
        assertEquals(uri, sentDto.getUri());
        assertEquals(ip, sentDto.getIp());
        assertEquals(timestampFormatted, sentDto.getTimestamp());
        assertNull(sentDto.getId()); // ID должен быть null
    }

    @Test
    void hit_RestClientExceptionHandled() {
        // Подготовка данных
        String app = "test-service";
        String uri = "/test/uri";
        String ip = "10.0.0.1";
        LocalDateTime now = LocalDateTime.now();

        // Имитация ошибки при вызове (например, сервер недоступен)
        when(restTemplate.postForEntity(eq("/hit"), any(HttpEntity.class), eq(Void.class)))
                .thenThrow(new RestClientException("Connection refused"));

        // Вызов метода (ожидаем, что не будет выброшено исключение)
        assertDoesNotThrow(() -> statsClient.hit(app, uri, ip, now));

        // Проверка: метод был вызван, и исключение было поймано
        verify(restTemplate).postForEntity(eq("/hit"), any(HttpEntity.class), eq(Void.class));
        // Дополнительно можно проверить логирование, но в unit-тестах это сложнее
    }

    // --- Тесты для метода getStats ---

    @Test
    void getStats_Success_WithUris_UniqueTrue() {
        // Подготовка данных
        LocalDateTime start = LocalDateTime.of(2023, 1, 1, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2023, 1, 31, 23, 59, 59);
        List<String> uris = Arrays.asList("/events/1", "/events/2");
        boolean unique = true;

        ViewStatsDto dto1 = new ViewStatsDto("app1", uris.get(0), 10L);
        ViewStatsDto dto2 = new ViewStatsDto("app2", uris.get(1), 5L);
        ViewStatsDto[] responseBody = new ViewStatsDto[]{dto1, dto2};

        // Имитация успешного ответа
        // Используем doReturn().when() для мока RestTemplate
        doReturn(new ResponseEntity<>(responseBody, HttpStatus.OK))
                .when(restTemplate).getForEntity(
                        urlTemplateCaptor.capture(),
                        eq(ViewStatsDto[].class),
                        uriVariablesCaptor.capture()
                );

        // Вызов метода
        List<ViewStatsDto> result = statsClient.getStats(start, end, uris, unique);

        // Проверки
        assertFalse(result.isEmpty());
        assertEquals(2, result.size());
        assertEquals(dto1, result.get(0));
        assertEquals(dto2, result.get(1));

        // Проверка URL-шаблона
        String expectedUrlTemplate = "/stats?start={start}&end={end}&unique={unique}&uris={uris0}&uris={uris1}";
        assertEquals(expectedUrlTemplate, urlTemplateCaptor.getValue());

        // Проверка параметров
        Map<String, ?> params = uriVariablesCaptor.getValue();
        assertEquals(5, params.size());
        assertEquals(start.format(FORMATTER), params.get("start"));
        assertEquals(end.format(FORMATTER), params.get("end"));
        assertEquals(unique, params.get("unique"));
        assertEquals(uris.get(0), params.get("uris0"));
        assertEquals(uris.get(1), params.get("uris1"));
    }

    @Test
    void getStats_Success_WithoutUris_UniqueFalse() {
        // Подготовка данных
        LocalDateTime start = LocalDateTime.of(2023, 10, 1, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2023, 10, 31, 23, 59, 59);
        boolean unique = false;

        ViewStatsDto dto1 = new ViewStatsDto("all", "/all/uris", 100L);
        ViewStatsDto[] responseBody = new ViewStatsDto[]{dto1};

        // Имитация успешного ответа
        doReturn(new ResponseEntity<>(responseBody, HttpStatus.OK))
                .when(restTemplate).getForEntity(
                        urlTemplateCaptor.capture(),
                        eq(ViewStatsDto[].class),
                        uriVariablesCaptor.capture()
                );

        // Вызов метода
        List<ViewStatsDto> result = statsClient.getStats(start, end, null, unique);

        // Проверки
        assertFalse(result.isEmpty());
        assertEquals(1, result.size());
        assertEquals(dto1, result.getFirst());

        // Проверка URL-шаблона (без uris)
        String expectedUrlTemplate = "/stats?start={start}&end={end}&unique={unique}";
        assertEquals(expectedUrlTemplate, urlTemplateCaptor.getValue());

        // Проверка параметров (без uris)
        Map<String, ?> params = uriVariablesCaptor.getValue();
        assertEquals(3, params.size());
        assertEquals(start.format(FORMATTER), params.get("start"));
        assertEquals(end.format(FORMATTER), params.get("end"));
        assertEquals(unique, params.get("unique"));
    }

    @Test
    void getStats_ServerReturnsEmptyBody() {
        // Подготовка данных
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now();

        // Имитация ответа с пустым телом, но статусом OK
        doReturn(new ResponseEntity<>((ViewStatsDto[]) null, HttpStatus.OK))
                .when(restTemplate).getForEntity(
                        anyString(),
                        eq(ViewStatsDto[].class),
                        anyMap()
                );

        // Вызов метода
        List<ViewStatsDto> result = statsClient.getStats(start, end, Collections.emptyList(), false);

        // Проверка: должен вернуться пустой список
        assertTrue(result.isEmpty());
    }

    @Test
    void getStats_ServerReturnsErrorStatus() {
        // Подготовка данных
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now();

        // Имитация ответа с ошибкой (например, 500 Internal Server Error)
        doReturn(new ResponseEntity<>((ViewStatsDto[]) null, HttpStatus.INTERNAL_SERVER_ERROR))
                .when(restTemplate).getForEntity(
                        anyString(),
                        eq(ViewStatsDto[].class),
                        anyMap()
                );

        // Вызов метода
        List<ViewStatsDto> result = statsClient.getStats(start, end, null, false);

        // Проверка: должен вернуться пустой список
        assertTrue(result.isEmpty());
    }

    @Test
    void getStats_RestClientExceptionHandled() {
        // Подготовка данных
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now();

        // Имитация ошибки RestClientException (проблемы с подключением)
        when(restTemplate.getForEntity(anyString(), eq(ViewStatsDto[].class), anyMap()))
                .thenThrow(new RestClientException("Server timeout"));

        // Вызов метода (ожидаем, что исключение будет поймано)
        List<ViewStatsDto> result = statsClient.getStats(start, end, null, false);

        // Проверка: должен вернуться пустой список
        assertTrue(result.isEmpty());
    }
}