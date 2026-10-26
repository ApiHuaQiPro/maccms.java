package com.maccms.backend.admin.application.view;

import java.util.List;

/**
 * 框架页的一个菜单分组。
 *
 * @param index 顶部导航与侧栏的序号（从 1 开始，旧模板的 {@code $i}）
 * @param icon  auth.php 的 icon 原文（如 {@code xe625}，旧模板原样写进 class）
 */
public record MenuGroupView(int index, String name, String icon, List<MenuItemView> items) {
}
