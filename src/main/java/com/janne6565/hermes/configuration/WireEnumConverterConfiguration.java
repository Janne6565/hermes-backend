package com.janne6565.hermes.configuration;

import java.util.Locale;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;
import org.springframework.format.FormatterRegistry;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Makes query parameters speak the same enum dialect as the JSON bodies.
 *
 * <p>Every enum in this API is stored uppercase and exposed lowercase, via {@code @JsonValue
 * wire()} and {@code @JsonCreator fromWire()}. Those two annotations are Jackson's, so they govern
 * request *bodies* only — a {@code @RequestParam Priority} is bound by Spring's own conversion
 * service, which calls {@code Enum.valueOf} and is case-sensitive. The result was a contract that
 * contradicted itself: the API hands out {@code "high"} everywhere and then rejects it with a 400
 * when the client sends it back as a filter.
 *
 * <p>Registering the lenient factory here rather than adding a converter per enum means a new wire
 * enum inherits the behaviour instead of quietly repeating the bug.
 */
@Configuration
public class WireEnumConverterConfiguration implements WebMvcConfigurer {

    @Override
    public void addFormatters(@NonNull FormatterRegistry registry) {
        registry.addConverterFactory(new CaseInsensitiveEnumConverterFactory());
    }

    /**
     * Uppercases before {@code valueOf}, which is exactly what the {@code fromWire} creators do.
     *
     * <p>Case-insensitive rather than lowercase-only on purpose: {@code ?priority=HIGH} typed by
     * hand against the OpenAPI page should work too. An unknown value still throws, so a typo is a
     * 400 rather than a silently ignored filter.
     */
    static class CaseInsensitiveEnumConverterFactory implements ConverterFactory<String, Enum> {

        @Override
        @NonNull public <T extends Enum> Converter<String, T> getConverter(@NonNull Class<T> targetType) {
            return source -> {
                String trimmed = source.trim();
                if (trimmed.isEmpty()) {
                    return null;
                }
                @SuppressWarnings({"unchecked", "rawtypes"})
                T value = (T) Enum.valueOf((Class<Enum>) targetType, upper(trimmed));
                return value;
            };
        }

        private static String upper(String value) {
            return value.toUpperCase(Locale.ROOT);
        }
    }
}
