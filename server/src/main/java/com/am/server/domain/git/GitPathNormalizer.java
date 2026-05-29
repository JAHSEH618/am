package com.am.server.domain.git;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 还原 Git core.quotepath / JSON 转义路径，入库与查询共用同一规则。
 * gz
 */
public final class GitPathNormalizer {

    private static final Pattern OCTAL = Pattern.compile("\\\\([0-7]{1,3})");

    private GitPathNormalizer() {
    }

    public static String normalize(String path) {
        if (path == null) {
            return "";
        }
        String s = path.trim();
        if (s.isEmpty()) {
            return s;
        }
        s = stripOuterQuotes(s);
        Matcher m = OCTAL.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int code = Integer.parseInt(m.group(1), 8);
            m.appendReplacement(out, Matcher.quoteReplacement(String.valueOf((char) code)));
        }
        m.appendTail(out);
        s = out.toString();
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    public static boolean pathsEqual(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    private static String stripOuterQuotes(String s) {
        for (int i = 0; i < 2; i++) {
            if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
                s = s.substring(1, s.length() - 1);
            } else {
                break;
            }
        }
        return s;
    }
}
