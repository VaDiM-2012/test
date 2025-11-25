package ru.practicum.stats.server.service;

import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.stats.dto.EndpointHitDto;
import ru.practicum.stats.dto.ViewStatsDto;
import ru.practicum.stats.server.exception.ValidationException; // ← импорт нового исключения
import ru.practicum.stats.server.mapper.EndpointHitMapper;
import ru.practicum.stats.server.repository.StatsRepository;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class StatsServiceImpl implements StatsService {

    private final StatsRepository statsRepository;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    @Transactional
    public void saveHit(EndpointHitDto endpointHitDto) {
        log.debug("Сохранение данных о посещении: app={}, uri={}, ip={}, time={}",
                endpointHitDto.getApp(),
                endpointHitDto.getUri(),
                endpointHitDto.getIp(),
                endpointHitDto.getTimestamp());

        statsRepository.save(EndpointHitMapper.toEntity(endpointHitDto));

        log.debug("Данные о посещении успешно сохранены в БД");
    }

    @Override
    public List<ViewStatsDto> getStats(String start, String end, List<String> uris, boolean unique) {
        log.debug("Запрос статистики: start={}, end={}, uris={}, unique={}", start, end, uris, unique);

        // 1. Парсим и валидируем временные границы
        LocalDateTime startTime = parseAndDecodeDateTime(start, "начало");
        LocalDateTime endTime = parseAndDecodeDateTime(end, "конец");

        validateTimeRange(startTime, endTime);

        // 2. Получаем данные из репозитория
        List<StatsRepository.ViewStatsProjection> projections = fetchStatsFromRepository(
                startTime, endTime, uris, unique
        );

        // 3. Преобразуем в DTO
        List<ViewStatsDto> result = projections.stream()
                .map(EndpointHitMapper::toViewStatsDto)
                .collect(Collectors.toList());

        log.debug("Статистика успешно получена. Количество записей: {}", result.size());
        return result;
    }

    /**
     * Декодирует URL-кодированную строку с датой и парсит её в LocalDateTime.
     */
    private LocalDateTime parseAndDecodeDateTime(String encodedDateTime, String label) {
        try {
            String decoded = URLDecoder.decode(encodedDateTime, StandardCharsets.UTF_8);
            return LocalDateTime.parse(decoded, FORMATTER);
        } catch (Exception e) {
            log.warn("Ошибка при разборе параметра времени '{}': {}", label, encodedDateTime, e);
            throw new ValidationException("Некорректный формат даты для параметра: " + label);
        }
    }

    /**
     * Проверяет, что начальное время не позже конечного.
     */
    private void validateTimeRange(LocalDateTime start, LocalDateTime end) {
        if (start.isAfter(end)) {
            log.warn("Некорректный временной диапазон: начало {} позже конца {}", start, end);
            throw new ValidationException("Дата начала не может быть позже даты окончания");
        }
    }

    /**
     * Выполняет запрос к репозиторию в зависимости от флага 'unique'.
     */
    private List<StatsRepository.ViewStatsProjection> fetchStatsFromRepository(
            LocalDateTime start,
            LocalDateTime end,
            @Nullable List<String> uris,
            boolean unique
    ) {
        boolean hasUris = uris != null && !uris.isEmpty();

        if (unique) {
            log.debug("Запрос уникальной статистики. Фильтр по URI: {}", hasUris ? uris : "отсутствует");
            return hasUris
                    ? statsRepository.findUniqueStatsWithUriFilter(start, end, uris)
                    : statsRepository.findUniqueStatsWithoutUriFilter(start, end);
        } else {
            log.debug("Запрос полной статистики. Фильтр по URI: {}", hasUris ? uris : "отсутствует");
            return hasUris
                    ? statsRepository.findAllStatsWithUriFilter(start, end, uris)
                    : statsRepository.findAllStatsWithoutUriFilter(start, end);
        }
    }
}