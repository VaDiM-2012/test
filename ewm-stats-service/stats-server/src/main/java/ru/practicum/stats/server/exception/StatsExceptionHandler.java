package ru.practicum.stats.server.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Глобальный обработчик исключений для сервиса статистики.
 * Обеспечивает единообразный ответ при ошибках.
 */
@RestControllerAdvice
@Slf4j
public class StatsExceptionHandler {

    @ExceptionHandler(ValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void handleValidationException(ValidationException e) {
        log.warn("Ошибка валидации: {}", e.getMessage());
        // Тело ответа пустое, как в спецификации (только статус)
    }

    // Можно добавить обработку других исключений при необходимости
}