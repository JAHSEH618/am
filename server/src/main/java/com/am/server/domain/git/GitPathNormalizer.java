package com.am.server.domain.git;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
        // quotepath 的八进制转义是 UTF-8 字节序列（如 员 = \345\221\230），必须按字节拼完
        // 再整体 UTF-8 解码；逐个 (char) cast 等于按 Latin-1 解释，多字节字符会成乱码。
        Matcher m = OCTAL.matcher(s);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int last = 0;
        while (m.find()) {
            out.writeBytes(s.substring(last, m.start()).getBytes(StandardCharsets.UTF_8));
            out.write(Integer.parseInt(m.group(1), 8));
            last = m.end();
        }
        out.writeBytes(s.substring(last).getBytes(StandardCharsets.UTF_8));
        s = new String(out.toByteArray(), StandardCharsets.UTF_8);
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
