package io.github.codeyumx.picofacetracking;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The privacy policy: PRIVACY.md and its translations in the repository root, copied into the app's assets by
 * app/build.gradle, so the app shows the same text as GitHub.
 */
final class PrivacyPolicy {
    private static final String PAGE = "https://github.com/codeyumx/PicoFaceTracking/blob/main/";

    private PrivacyPolicy() {
    }

    /** PRIVACY.md for English, PRIVACY.<code>.md for the other languages. */
    private static String file(Language language) {
        return language == Language.ENGLISH ? "PRIVACY.md" : "PRIVACY." + language.code + ".md";
    }

    /** The policy's page on GitHub, in the language shown. */
    static String url(Context context) {
        return PAGE + file(Language.shown(context));
    }

    /** The policy in the language shown, as styled text without its title (the dialog has one). */
    static CharSequence text(Context context) throws IOException {
        String markdown;
        try (InputStream in = context.getAssets().open(file(Language.shown(context)))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; )
                out.write(buffer, 0, n);
            markdown = new String(out.toByteArray(), StandardCharsets.UTF_8).replace("\r", "");
        }
        return render(markdown);
    }

    /**
     * The subset of Markdown the policy uses: "# " title (left out), "## " headings (bold), "- " list items, wrapped
     * lines, **bold**, `code` and [links](url) (their text).
     */
    private static CharSequence render(String markdown) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        for (String block : markdown.trim().split("\n\\s*\n")) {
            String[] lines = block.trim().split("\n");
            if (lines[0].startsWith("# "))
                continue;
            if (out.length() > 0)
                out.append("\n\n");
            if (lines[0].startsWith("## ")) {
                int start = out.length();
                out.append(lines[0].substring(3).trim());
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }
            StringBuilder item = new StringBuilder();
            boolean list = lines[0].startsWith("- ");
            for (String line : lines) {
                String trimmed = line.trim();
                if (list && trimmed.startsWith("- ")) {
                    if (item.length() > 0) {
                        out.append("• ").append(inline(item.toString())).append("\n");
                        item.setLength(0);
                    }
                    trimmed = trimmed.substring(2);
                }
                // Lines of Japanese and Chinese join without a space; wrapped English lines join with one.
                if (item.length() > 0 && !trimmed.isEmpty() && trimmed.charAt(0) < 0x2E80)
                    item.append(' ');
                item.append(trimmed);
            }
            out.append(list ? "• " : "").append(inline(item.toString()));
        }
        return out;
    }

    private static String inline(String text) {
        return text.replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1").replace("**", "").replace("`", "");
    }
}
