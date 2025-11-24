package ru.practicum.stats.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;
import ru.practicum.stats.dto.EndpointHitDto;
import ru.practicum.stats.dto.ViewStatsDto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * HTTP-клиент для сервиса статистики.
 * Позволяет:
 * - Отправлять информацию о посещении (hit)
 * - Получать статистику просмотров
 */
@Component
@Slf4j
public class StatsClient {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final RestTemplate restTemplate;

    @Autowired
    public StatsClient(@Value("${stats-server.url}") String serverUrl, RestTemplateBuilder builder) {
        this.restTemplate = builder
                .uriTemplateHandler(new DefaultUriBuilderFactory(serverUrl))
                .requestFactory(HttpComponentsClientHttpRequestFactory.class)
                .build();
    }

    /**
     * Отправляет информацию о входящем запросе в сервис статистики.
     *
     * @param app       Название сервиса, от которого пришёл запрос (например, "ewm-main-service").
     * @param uri       URI запрошенного ресурса (например, "/events/123").
     * @param ip        IP-адрес клиента, совершившего запрос.
     * @param timestamp Временная метка, когда был зафиксирован запрос.
     */
    public void hit(String app, String uri, String ip, LocalDateTime timestamp) {
        // Формируем DTO-объект с данными о "хите" для отправки в сервис статистики.
        // ID оставляем null — он будет сгенерирован на стороне сервера статистики.
        EndpointHitDto hitDto = new EndpointHitDto(
                null,
                app,
                uri,
                ip,
                timestamp.format(FORMATTER)  // Преобразуем LocalDateTime в строку в формате "yyyy-MM-dd HH:mm:ss"
        );

        // Указываем, что тело запроса будет в формате JSON.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Оборачиваем DTO и заголовки в HTTP-сущность для отправки.
        HttpEntity<EndpointHitDto> requestEntity = new HttpEntity<>(hitDto, headers);

        try {
            // Отправляем POST-запрос на эндпоинт "/hit" сервиса статистики.
            // Используем postForEntity — он предназначен специально для POST-запросов.
            restTemplate.postForEntity("/hit", requestEntity, Void.class);

            // Успешная отправка: логируем отладочную информацию.
            log.debug("Данные о запросе успешно отправлены в сервис статистики: app={}, uri={}, ip={}, timestamp={}",
                    app, uri, ip, timestamp);

        } catch (Exception e) {
            // В случае ошибки (недоступность сервиса, таймаут и т.п.) логируем предупреждение.
            // Исключение НЕ пробрасывается выше, так как сбор статистики не критичен
            // для основной бизнес-логики приложения.
            log.warn("Не удалось отправить данные о запросе в сервис статистики: {}", e.getMessage());
        }
    }

    /**
     * Получает статистику просмотров за заданный период из внешнего сервиса статистики.
     *
     * @param start  Начало временного диапазона (включительно). Не может быть null.
     * @param end    Конец временного диапазона (включительно). Не может быть null.
     * @param uris   Список URI для фильтрации статистики. Может быть null или пустым — в этом случае
     *               возвращается статистика по всем URI.
     * @param unique Флаг, указывающий, нужно ли учитывать только уникальные IP-адреса.
     * @return Список объектов {@link ViewStatsDto} с агрегированной статистикой.
     *         Возвращается пустой список в случае ошибки или отсутствия данных.
     */
    public List<ViewStatsDto> getStats(
            @NonNull LocalDateTime start,
            @NonNull LocalDateTime end,
            @Nullable List<String> uris,
            boolean unique
    ) {
        // 1. Формируем параметры запроса
        Map<String, Object> queryParams = buildQueryParameters(start, end, uris, unique);

        // 2. Формируем URL-шаблон с поддержкой динамического количества URI
        String urlTemplate = buildStatsUrlTemplate(uris);

        // 3. Выполняем запрос и обрабатываем ответ
        return sendStatsRequest(urlTemplate, queryParams);
    }

    /**
     * Формирует карту параметров для подстановки в URL запроса к сервису статистики.
     * Все строковые параметры (время, URI) предварительно URL-кодируются.
     *
     * @param start  Начало временного диапазона.
     * @param end    Конец временного диапазона.
     * @param uris   Список URI для фильтрации (может быть null).
     * @param unique Флаг уникальности по IP.
     * @return Карта параметров, готовая к использованию с {@link RestTemplate}.
     */
    private Map<String, Object> buildQueryParameters(
            LocalDateTime start,
            LocalDateTime end,
            @Nullable List<String> uris,
            boolean unique
    ) {
        Map<String, Object> params = new HashMap<>();

        // Кодируем временные метки в соответствии с ожидаемым форматом сервиса
        params.put("start", start.format(FORMATTER));
        params.put("end", end.format(FORMATTER));
        params.put("unique", unique);

        // Добавляем URI, если они заданы
        if (uris != null && !uris.isEmpty()) {
            for (int i = 0; i < uris.size(); i++) {
                String paramName = "uris" + i;
                params.put(paramName, uris.get(i));
            }
        }

        return params;
    }

    /**
     * Формирует шаблон URL для запроса статистики с поддержкой переменного числа параметров {@code uris}.
     * Используется совместно с картой параметров, где URI представлены как {@code uris0}, {@code uris1}, и т.д.
     *
     * @param uris Список URI (может быть null или пустым).
     * @return Шаблон URL с подстановочными переменными, например:
     *         {@code /stats?start={start}&end={end}&unique={unique}&uris={uris0}&uris={uris1}}
     */
    private String buildStatsUrlTemplate(@Nullable List<String> uris) {
        StringBuilder url = new StringBuilder("/stats?start={start}&end={end}&unique={unique}");

        if (uris != null && !uris.isEmpty()) {
            for (int i = 0; i < uris.size(); i++) {
                url.append("&uris={uris").append(i).append("}");
            }
        }

        return url.toString();
    }

    /**
     * Выполняет HTTP GET-запрос к сервису статистики и обрабатывает ответ.
     * В случае ошибки или недопустимого статуса возвращает пустой список.
     *
     * @param urlTemplate Шаблон URL с переменными подстановки.
     * @param queryParams Параметры запроса для подстановки в URL.
     * @return Список статистики или пустой список при ошибке.
     */
    private List<ViewStatsDto> sendStatsRequest(String urlTemplate, Map<String, Object> queryParams) {
        try {
            ResponseEntity<ViewStatsDto[]> response = restTemplate.getForEntity(
                    urlTemplate,
                    ViewStatsDto[].class,
                    queryParams
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                ViewStatsDto[] body = response.getBody();
                log.debug("Получена статистика: {} записей", body.length);
                return Arrays.asList(body);
            } else {
                log.warn("Сервис статистики вернул пустой или некорректный ответ. Статус: {}",
                        response.getStatusCode());
                return Collections.emptyList();
            }
        } catch (RestClientException e) {
            log.error("Ошибка при обращении к сервису статистики: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}