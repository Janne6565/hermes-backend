package com.janne6565.hermes.model.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Which mail service a message or account came from. */
public enum MailProviderType {
    GMAIL("https://mail.google.com/mail/u/0/#inbox/"),
    // Personal accounts. Work/school mailboxes live on outlook.office.com; if one is ever
    // connected this becomes a per-account value rather than a per-type one.
    OUTLOOK("https://outlook.live.com/mail/0/inbox/id/");

    private final String deepLinkPrefix;

    MailProviderType(String deepLinkPrefix) {
        this.deepLinkPrefix = deepLinkPrefix;
    }

    /**
     * Link into the provider's web client.
     *
     * <p>Lives on the type rather than only on the provider bean because {@code MessageDto.from} is
     * a static mapper with no bean to ask — and two copies of a URL template is exactly the kind of
     * thing that silently diverges.
     */
    public String deepLink(String externalId) {
        return deepLinkPrefix + externalId;
    }

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static MailProviderType fromWire(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
