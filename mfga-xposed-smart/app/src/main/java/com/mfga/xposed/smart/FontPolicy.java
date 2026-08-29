package com.mfga.xposed.smart;

import java.util.Locale;

/** Conservative policy: replace ordinary bundled text fonts, preserve likely icon/emoji/symbol/math/mono fonts. */
public final class FontPolicy {
    private FontPolicy() {}

    private static final String[] PRESERVE_TOKENS = {
            "icon", "icons", "materialicons", "material-symbol", "material_symbols",
            "fontawesome", "fa-solid", "fa-regular", "fa-brands", "icomoon", "glyphicons",
            "emoji", "notoemoji", "twemoji", "symbol", "symbols", "dingbat",
            "math", "stix", "cambria", "music", "braille",
            "mono", "monospace", "code", "terminal", "nerd", "codicon", "octicon"
    };

    public static boolean shouldPreserveSource(String source) {
        if (source == null || source.isEmpty()) return false;
        String s = source.toLowerCase(Locale.ROOT).replace('\\', '/');
        for (String token : PRESERVE_TOKENS) {
            if (s.contains(token)) return true;
        }
        return false;
    }

    public static boolean shouldPreserveResourceName(String name) {
        return shouldPreserveSource(name);
    }
}
