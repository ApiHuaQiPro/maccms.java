package com.maccms.backend.admin.web;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 后台的地址空间（21 文档第 7 节），由部署参数 {@code maccms.admin.entry}、{@code maccms.admin.base-url} 校验得到。
 *
 * <ul>
 * <li>本应用内的路由：{@code entry}（如 {@code /_admin.php}）及其下的 {@code /admin/控制器/动作…}；与站点相同，base-url 带子目录时由
 * 反向代理把前缀映射到本应用根路径。</li>
 * <li>页面里的站内路径：{@link #root()}（{@code base 路径 + entry}，旧 {@code $_SERVER['SCRIPT_NAME']}、{@code url()} 的前缀）、
 * {@link #sitePath()}（旧 {@code __ROOT__}，部署前缀，根目录部署时为空串）。</li>
 * <li>会话 cookie：Path 为 {@link #root()}，base-url 为 https 时加 Secure。除登录页与框架页外，只接受来源为 {@link #origin()} 的请求。</li>
 * </ul>
 *
 * @param entry    本应用内的入口路径（{@code /名称.php}）
 * @param sitePath 对外的部署前缀（不以 / 结尾；根目录部署为空串）
 * @param origin   对外地址的来源（{@code scheme://host[:port]}，小写）
 */
public record AdminEndpoint(String entry, String sitePath, String origin, boolean secure) {

    private static final Pattern ENTRY = Pattern.compile("/[A-Za-z0-9_-]{1,64}\\.php");
    /** 不能用作后台入口的文件名：旧入口拒绝 admin.php；index.php、api.php、install.php 是前台、接口与安装入口。 */
    private static final Set<String> RESERVED = Set.of("/admin.php", "/index.php", "/api.php", "/install.php");

    /** 对外的入口路径（部署前缀 + 入口），旧 {@code SCRIPT_NAME}。 */
    public String root() {
        return sitePath + entry;
    }

    /** 由部署参数得到；缺失或不合法时启动失败，信息只含配置项名称。 */
    public static AdminEndpoint from(String entry, String baseUrl) {
        List<String> errors = new ArrayList<>();
        String e = entry == null ? "" : entry.strip();
        if (!ENTRY.matcher(e).matches() || RESERVED.contains(e.toLowerCase(Locale.ROOT))) {
            errors.add("maccms.admin.entry（必填，形如 /_admin.php 的单段 .php 路径，不能是 /admin.php、/index.php、/api.php、/install.php）");
        }
        URI uri = parse(baseUrl == null ? "" : baseUrl.strip());
        if (uri == null) {
            errors.add("maccms.admin.base-url（必填，http/https 绝对地址，有主机名，不含用户信息、查询串、片段，路径以 / 结尾）");
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("admin 已启用但后台配置缺失或不合法：" + String.join("；", errors));
        }
        String path = uri.getRawPath();
        String sitePath = path.substring(0, path.length() - 1);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        // 浏览器的 Origin 不写默认端口（https://h:443 → https://h）
        int port = uri.getPort();
        boolean defaultPort = port < 0 || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
        String origin = scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
        return new AdminEndpoint(e, sitePath, origin, scheme.equals("https"));
    }

    private static URI parse(String raw) {
        if (raw.isEmpty() || raw.length() > 200) {
            return null;
        }
        try {
            URI uri = new URI(raw);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getRawPath() == null || !uri.getRawPath().startsWith("/")
                    || !uri.getRawPath().endsWith("/") || uri.getRawPath().contains("//")
                    || !uri.getRawPath().matches("[A-Za-z0-9/._~-]*")) {
                return null;
            }
            return uri;
        } catch (URISyntaxException ex) {
            return null;
        }
    }
}
