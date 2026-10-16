package io.github.codeyumx.picofacetracking;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/** The languages of the settings screen and notification, picked with the flags at the top of the settings screen. */
enum Language {
    ENGLISH("en", Locale.ENGLISH, R.string.language_english),
    JAPANESE("ja", Locale.JAPANESE, R.string.language_japanese),
    CHINESE("zh", Locale.SIMPLIFIED_CHINESE, R.string.language_chinese);

    /** Saved in Prefs, and the language_code string of the matching resources. */
    final String code;
    final Locale locale;
    /** Flag and name of the language, in that language. */
    final int label;

    Language(String code, Locale locale, int label) {
        this.code = code;
        this.locale = locale;
        this.label = label;
    }

    /** The context with this language's resources. */
    Context in(Context base) {
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocale(locale);
        return base.createConfigurationContext(configuration);
    }

    /** The context in the chosen language; unchanged (the headset's language) when none was chosen. */
    static Context apply(Context base) {
        String code = new Prefs(base).language();
        for (Language language : values()) {
            if (language.code.equals(code))
                return language.in(base);
        }
        return base;
    }

    /** The language the context's resources are in. */
    static Language shown(Context context) {
        String code = context.getString(R.string.language_code);
        for (Language language : values()) {
            if (language.code.equals(code))
                return language;
        }
        return ENGLISH;
    }
}
