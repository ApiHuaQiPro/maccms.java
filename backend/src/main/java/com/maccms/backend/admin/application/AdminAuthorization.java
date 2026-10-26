package com.maccms.backend.admin.application;

/**
 * 旧 {@code Base::check_auth}（{@code application/admin/controller/Base.php:42-58}）：超级管理员（{@code admin_id == 1}）一律放行；
 * 其他管理员在 {@code admin_auth . ',index/index,index/welcome,'} 中按子串查找 {@code ,控制器/动作,}（控制器、动作按 ASCII 转小写，
 * {@code admin_auth} 本身不转换、不去空白）。所以 {@code admin_auth} 缺首逗号的第一项、大写的项、带空格的项都不生效，缺尾逗号的最后一项照样生效
 * （黄金 {@code auth-string-*}）。
 *
 * <p>与旧版的差异只有一条（D-B12-6）：{@code index/logout} 不做权限检查——旧版非超级管理员的 {@code admin_auth} 里不会有它（权限表单里
 * 没有这个复选框），点"退出登陆"只会得到"您没有权限访问此页面"，无法退出（黄金 {@code auth-viewer}、{@code auth-editor}）。
 */
public final class AdminAuthorization {

    /** 旧版对所有管理员默认追加的权限（Base.php:47）。 */
    static final String DEFAULT_AUTHS = ",index/index,index/welcome,";

    private AdminAuthorization() {
    }

    /**
     * @param controller 请求的控制器（任意大小写）
     * @param action     请求的动作（任意大小写）
     */
    public static boolean allowed(long adminId, String adminAuth, String controller, String action) {
        if (adminId == 1) {
            return true;
        }
        String c = asciiLower(controller);
        String a = asciiLower(action);
        if (c.equals("index") && a.equals("logout")) {
            return true;
        }
        String auths = (adminAuth == null ? "" : adminAuth) + DEFAULT_AUTHS;
        return auths.contains("," + c + "/" + a + ",");
    }

    /** PHP {@code strtolower}（C 区域设置）：只改 ASCII 字母。 */
    public static String asciiLower(String s) {
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 'A' && ch <= 'Z') {
                if (sb == null) {
                    sb = new StringBuilder(s);
                }
                sb.setCharAt(i, (char) (ch + 32));
            }
        }
        return sb == null ? s : sb.toString();
    }
}
