package com.maccms.backend.admin.application.view;

/**
 * 框架页侧栏的一项。
 *
 * @param key    auth.php 的条目键（快捷菜单为 13、14 + 行号）
 * @param url    旧模板写进 href 的地址；恰好是 {@code ###} 时显示为分隔线
 * @param dataId 旧模板的 {@code data-id}：条目键与组内序号直接拼接
 */
public record MenuItemView(int key, String name, String url, String dataId) {

    /** 旧模板 {@code $vv['url'] !== '###'} 之外的项显示为分隔线。 */
    public boolean separator() {
        return "###".equals(url);
    }

    /**
     * "自定义菜单区域"分隔项（地址固定为 {@code javascript:void(0);return false;}）。模板把它写成固定文字：jte 不输出
     * {@code a@href} 里以 {@code javascript:} 开头的动态值。
     */
    public boolean quickTitle() {
        return com.maccms.backend.admin.application.AdminMenus.QUICK_TITLE_URL.equals(url);
    }

    /** 快捷菜单里配置的 {@code javascript:} 地址（新版固定写成 {@code javascript:void(0);}，D-B12-13）。 */
    public boolean script() {
        return url != null && url.startsWith("javascript:") && !quickTitle();
    }
}
