package cafe.server.i18n;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.function.Consumer;

/**
 * Tiny i18n helper around {@link ResourceBundle}.
 *
 * <p>Holds the current locale, supplies translated strings, and lets the
 * UI register a listener so menus + columns re-label themselves when the
 * user toggles language.
 */
public final class Messages {

    private static final String BUNDLE = "i18n.messages";

    private Locale locale;
    private ResourceBundle bundle;
    private final List<Consumer<Locale>> listeners = new ArrayList<>();

    public Messages(Locale initial) {
        setLocale(initial);
    }

    public Locale getLocale() { return locale; }

    public void setLocale(Locale newLocale) {
        this.locale = newLocale;
        this.bundle = ResourceBundle.getBundle(BUNDLE, newLocale);
        for (Consumer<Locale> l : listeners) l.accept(newLocale);
    }

    public String get(String key) {
        try { return bundle.getString(key); }
        catch (Exception e) { return key; }
    }

    public String format(String key, Object... args) {
        return new MessageFormat(get(key), locale).format(args);
    }

    /** UI components subscribe so they can re-label themselves. */
    public void onLocaleChange(Consumer<Locale> listener) {
        listeners.add(listener);
        listener.accept(locale);
    }
}
