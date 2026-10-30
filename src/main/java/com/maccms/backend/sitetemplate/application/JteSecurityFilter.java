package com.maccms.backend.sitetemplate.application;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在线编辑 jte 模板的安全过滤（路线图第 5.6 节第 4 点，24 文档第 7.3 节）："命中就拒绝"，规则针对 jte 语法。jte 模板会被编译成 Java 代码
 * 运行，这只是纵深防御，不是沙箱（风险见路线图第 6 节）。
 * <ol>
 * <li>{@code @import} 只能是内置模板本身用到的那些（视图模型、{@code gg.jte.Content}、{@code java.util.List/Optional}），其余一律拒绝；</li>
 * <li>{@code <script>}、{@code <iframe>} 只能出现在内置版本的同名模板本身就有的模板里（B05/B09/B10 已裁定的例外）；</li>
 * <li>{@code @import} 行以外不得出现 {@link #RULES} 中的任何写法：代码块 {@code !{}、不转义输出 {@code $unsafe{}/{@code @raw}、
 * {@code new}、类字面量与反射、进程/系统/线程、文件与网络 API、全限定类名（{@code java.}、{@code com.maccms.} 等）、
 * Unicode 转义 {@code \}{@code u}（javac 在分词前就解码它，可以拼出被禁止的写法）。</li>
 * </ol>
 */
public final class JteSecurityFilter {

    /** 规则名 → 正则。规则名出现在拒绝提示里。 */
    static final Map<String, Pattern> RULES = rules();

    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*@import\\s+(\\S+)\\s*$");
    private static final Pattern IMPORT_LINE = Pattern.compile("(?m)^\\s*@import\\b.*$");
    private static final Pattern SCRIPT_OR_FRAME = Pattern.compile("(?i)<\\s*(script|iframe)\\b");

    private final Set<String> allowedImports;
    private final Map<String, Set<String>> builtinTags;

    /** @param builtin 内置模板源码（模板名 → 内容）：允许的 import 与各模板允许的 script/iframe 都取自这里 */
    public JteSecurityFilter(Map<String, byte[]> builtin) {
        Set<String> imports = new TreeSet<>();
        Map<String, Set<String>> tags = new java.util.HashMap<>();
        for (Map.Entry<String, byte[]> e : builtin.entrySet()) {
            String text = new String(e.getValue(), StandardCharsets.UTF_8);
            Matcher m = IMPORT.matcher(text);
            while (m.find()) {
                imports.add(m.group(1));
            }
            Set<String> t = new TreeSet<>();
            Matcher s = SCRIPT_OR_FRAME.matcher(text);
            while (s.find()) {
                t.add(s.group(1).toLowerCase(Locale.ROOT));
            }
            tags.put(e.getKey(), t);
        }
        this.allowedImports = Set.copyOf(imports);
        this.builtinTags = Map.copyOf(tags);
    }

    public Set<String> allowedImports() {
        return allowedImports;
    }

    /** 违反的规则（空表示通过）。每项为一句可直接显示的说明。 */
    public List<String> check(String template, String content) {
        List<String> out = new ArrayList<>();
        Matcher im = IMPORT_LINE.matcher(content);
        while (im.find()) {
            Matcher one = IMPORT.matcher(im.group());
            String target = one.find() ? one.group(1) : im.group().strip();
            if (!allowedImports.contains(target)) {
                out.add("不允许的 @import：" + abbreviate(target));
            }
        }
        String code = IMPORT_LINE.matcher(content).replaceAll("");
        for (Map.Entry<String, Pattern> r : RULES.entrySet()) {
            if (r.getValue().matcher(code).find()) {
                out.add("不允许的写法：" + r.getKey());
            }
        }
        Set<String> allowedTags = builtinTags.getOrDefault(template, Set.of());
        Matcher s = SCRIPT_OR_FRAME.matcher(content);
        Set<String> seen = new TreeSet<>();
        while (s.find()) {
            String tag = s.group(1).toLowerCase(Locale.ROOT);
            if (!allowedTags.contains(tag) && seen.add(tag)) {
                out.add("这个模板不允许 <" + tag + ">（只有内置版本本身含有它的模板可以使用）");
            }
        }
        return out;
    }

    private static Map<String, Pattern> rules() {
        Map<String, Pattern> m = new java.util.LinkedHashMap<>();
        m.put("!{…} 代码块", Pattern.compile("!\\{"));
        m.put("$unsafe{…} 不转义输出", Pattern.compile("\\$unsafe\\{"));
        m.put("@raw 不转义区块", Pattern.compile("@raw\\b"));
        m.put("Unicode 转义 \\u", Pattern.compile("\\\\+u"));
        // 后面紧跟 "-" 的不是 Java（autocomplete="new-password"）
        m.put("new 创建对象", Pattern.compile("\\bnew\\b(?!-)"));
        m.put(".class 类字面量", Pattern.compile("\\.\\s*class\\b"));
        m.put("Class/ClassLoader", Pattern.compile("\\b(Class|ClassLoader|forName|getClass|getClassLoader)\\b"));
        m.put("反射", Pattern.compile("\\b(reflect|invoke|getMethod|getMethods|getDeclared\\w*|getField|getFields|setAccessible"
                + "|MethodHandles?|VarHandle|Unsafe|Proxy|Lookup)\\b"));
        m.put("进程与系统", Pattern.compile("\\b(Runtime|ProcessBuilder|ProcessHandle|Process|System|exec|exit|halt|loadLibrary"
                + "|Thread|ThreadLocal|ScriptEngine\\w*|SecurityManager|ModuleLayer)\\b"));
        m.put("文件与网络", Pattern.compile("\\b(File|Files|Path|Paths|FileSystems?|RandomAccessFile|\\w*Stream|\\w*Reader|\\w*Writer"
                + "|URL|URI|URLConnection|HttpURLConnection|HttpClient|Socket|ServerSocket|DatagramSocket|InetAddress"
                + "|Channels?|\\w*Channel)\\b"));
        m.put("全限定类名", Pattern.compile("\\b(java|javax|jdk|sun|com|org|net|gg|io|kotlin|groovy|scala)\\s*\\.\\s*[a-z]"));
        m.put("Spring 容器", Pattern.compile("\\b(ApplicationContext|BeanFactory|getBean|SpringApplication|Environment)\\b"));
        m.put("static 等 Java 修饰符", Pattern.compile("\\b(static|synchronized|native|transient|volatile|instanceof)\\b"
                + "|\\bimport\\s+static\\b"));
        return java.util.Collections.unmodifiableMap(m);
    }

    private static String abbreviate(String s) {
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}
