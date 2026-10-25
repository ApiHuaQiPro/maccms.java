package com.maccms.backend.admin.application;

import com.maccms.backend.admin.application.view.AdminPrincipal;
import com.maccms.backend.admin.application.view.MenuGroupView;
import com.maccms.backend.admin.application.view.MenuItemView;
import com.maccms.backend.common.lang.LegacyLang;
import java.util.ArrayList;
import java.util.List;

/**
 * 框架页的菜单（旧 {@code Index::index}，admin/controller/Index.php:31-87，模板 admin/view/index/index.html:72-95）：
 * <ol>
 * <li>auth.php 中 {@code show == 1} 且当前管理员有权限的条目，地址为 {@code url('admin/控制器/动作')}，有 {@code param} 时加 {@code ?param}；
 * 其余条目去掉，去掉后为空的分组也去掉。</li>
 * <li>快捷菜单不为空时，在第 1 组末尾加一项"↓↓↓自定义菜单区域↓↓↓"（地址 {@code javascript:void(0);return false;}），再逐行加入快捷菜单：
 * 跳过空行（PHP {@code empty()}）；{@code trim} 后按逗号拆成"名称,地址"；地址以 {@code http}、{@code //}、{@code /} 开头或含
 * {@code ###}、{@code javascript:} 时原样，否则按 {@code url()} 生成（没有地址时是当前页 {@code index/index}）。</li>
 * <li>侧栏每项的 {@code data-id} 是"条目键 + 组内序号（从 1 开始）"，顶部导航的序号从 1 开始（模板的 {@code $key}、{@code $kk}、{@code $i}）。</li>
 * </ol>
 * 快捷菜单里 {@code javascript:} 开头的地址新版改成固定的 {@code javascript:void(0);}（D-B12-13）。
 */
public final class AdminMenus {

    /** 旧"自定义菜单区域"分隔项的地址（Index.php:56）。 */
    public static final String QUICK_TITLE_URL = "javascript:void(0);return false;";

    private AdminMenus() {
    }

    /**
     * @param quickmenu 快捷菜单的各行（旧 {@code config('quickmenu')}；为空时旧版改读 {@code application/data/config/quickmenu.txt}，
     *                  仓库与备份里都没有这个文件，新版按空串处理——效果相同：只加分隔项，没有菜单行）
     * @param url       当前请求（{@code index/index}）下的 {@link LegacyAdminUrl}
     */
    public static List<MenuGroupView> build(AdminPrincipal admin, LegacyLang lang, List<String> quickmenu,
                                            LegacyAdminUrl url) {
        List<MenuGroupView> out = new ArrayList<>();
        for (AdminPermissions.Group g : AdminPermissions.groups()) {
            List<MenuItemView> items = new ArrayList<>();
            for (AdminPermissions.Entry e : g.entries()) {
                if (!e.show() || !AdminAuthorization.allowed(admin.adminId(), admin.adminAuth(), e.controller(), e.action())) {
                    continue;
                }
                String href = e.action().contains("javascript") ? e.action()
                        : url.build("admin/" + e.controller() + "/" + e.action());
                if (!e.param().isEmpty()) {
                    href += "?" + e.param();
                }
                items.add(new MenuItemView(e.key(), e.name().resolve(lang::get), href, ""));
            }
            if (g.key() == 1) {
                appendQuickmenu(items, lang, quickmenu, url);
            }
            if (items.isEmpty()) {
                continue;
            }
            List<MenuItemView> numbered = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                MenuItemView it = items.get(i);
                numbered.add(new MenuItemView(it.key(), it.name(), it.url(), it.key() + String.valueOf(i + 1)));
            }
            out.add(new MenuGroupView(out.size() + 1, g.name().resolve(lang::get), g.icon(), List.copyOf(numbered)));
        }
        return List.copyOf(out);
    }

    private static void appendQuickmenu(List<MenuItemView> items, LegacyLang lang, List<String> quickmenu,
                                        LegacyAdminUrl url) {
        List<String> lines = quickmenu == null || quickmenu.isEmpty() ? List.of("") : quickmenu;
        items.add(new MenuItemView(13, lang.get("admin/index/quick_tit"), QUICK_TITLE_URL, ""));
        for (int k = 0; k < lines.size(); k++) {
            String v = lines.get(k);
            if (v == null || v.isEmpty() || v.equals("0")) {
                continue;
            }
            String[] one = phpTrim(v).split(",", -1);
            String name = one[0];
            String target = one.length > 1 ? one[1] : null;
            String href;
            if (target != null && (target.startsWith("http") || target.startsWith("//") || target.startsWith("/"))) {
                href = target;
            } else if (target != null && target.contains("javascript:")) {
                href = "javascript:void(0);";
            } else if (target != null && target.contains("###")) {
                href = target;
            } else {
                href = url.build(target);
            }
            items.add(new MenuItemView(14 + k, name, href, ""));
        }
    }

    /** PHP {@code trim()} 的默认字符集。 */
    static String phpTrim(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && " \t\n\r\0\u000B".indexOf(s.charAt(a)) >= 0) {
            a++;
        }
        while (b > a && " \t\n\r\0\u000B".indexOf(s.charAt(b - 1)) >= 0) {
            b--;
        }
        return s.substring(a, b);
    }
}
