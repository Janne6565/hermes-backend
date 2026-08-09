package com.janne6565.hermes.services.mail;

import com.janne6565.hermes.client.MailProvider;
import com.janne6565.hermes.model.core.MailProviderType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Resolves a {@link MailProvider} by type. Spring supplies whatever implementations exist. */
@Component
public class MailProviderRegistry {

    private final Map<MailProviderType, MailProvider> byType =
            new EnumMap<>(MailProviderType.class);

    public MailProviderRegistry(List<MailProvider> providers) {
        for (MailProvider provider : providers) {
            byType.put(provider.type(), provider);
        }
    }

    /**
     * @throws IllegalStateException when an account references a provider that isn't built yet —
     *     which can only happen if a row survives a rollback that removed its implementation.
     *     Failing loudly beats silently never syncing that mailbox again.
     */
    public MailProvider forType(MailProviderType type) {
        MailProvider provider = byType.get(type);
        if (provider == null) {
            throw new IllegalStateException("No mail provider implementation for " + type);
        }
        return provider;
    }

    public boolean supports(MailProviderType type) {
        return byType.containsKey(type);
    }
}
