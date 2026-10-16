package io.github.codeyumx.picofacetracking;

import android.content.Context;

import java.util.Arrays;

/**
 * A message made on a background thread (tracking and pairing status), kept as a string resource and its arguments so
 * it is shown in the language chosen at the time it is shown. Arguments may be Texts themselves.
 */
final class Text {
    /** The string resource, or 0 for a literal (args[0]), such as an exception message. */
    final int id;
    private final Object[] args;

    Text(int id, Object... args) {
        this.id = id;
        this.args = args;
    }

    /** Text that is not translated, such as a system error message. */
    static Text literal(String text) {
        return new Text(0, text);
    }

    String get(Context context) {
        if (id == 0)
            return String.valueOf(args[0]);
        if (args.length == 0)
            return context.getString(id);
        Object[] resolved = args.clone();
        for (int i = 0; i < resolved.length; i++) {
            if (resolved[i] instanceof Text)
                resolved[i] = ((Text) resolved[i]).get(context);
        }
        return context.getString(id, resolved);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Text && ((Text) other).id == id && Arrays.equals(((Text) other).args, args);
    }

    @Override
    public int hashCode() {
        return 31 * id + Arrays.hashCode(args);
    }
}
