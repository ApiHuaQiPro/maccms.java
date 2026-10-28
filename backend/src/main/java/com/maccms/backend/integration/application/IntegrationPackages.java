package com.maccms.backend.integration.application;

import java.util.Map;

/**
 * {@code integration} 白名单的登记表（B23，路线图第 4.3 节 ④；docs/architecture/26-b23-integration-foundation.md 第 2 节）。
 * 包名都相对 {@code com.maccms.backend}。
 *
 * <ul>
 * <li>{@link #RAW_API}：可以直接使用 JDK 等出站网络 API 的子包。B23 只有出站客户端 {@link #CLIENT} 本身；
 * 以后只有不走 HTTP 的协议（如 B25 的 SMTP）才可能另行登记，并在该批文档里说明它如何执行同一套目标主机检查。</li>
 * <li>{@link #REGISTERED}：可以使用出站客户端（{@code integration.http}）发请求的子包 → 登记批次。B23 交付时为空，B28 登记了第一批；
 * B24～B29 每批只登记本批的子包（{@code integration.pay}、{@code integration.sms} 等），登记改动必须出现在该批的合并提交中。
 * E01/E02/E03/E08 的本地版本、采集链、上传与图片处理永远不登记；E05、E12 是入站接口，不需要登记。</li>
 * </ul>
 *
 * <p>两张表同时约束编译期与运行期：{@code OutboundNetworkGuardTest} 按它们静态扫描 {@code src/main}；出站客户端每次发请求前用
 * {@link #isRegistered} 检查直接调用方所在的包，未登记即拒绝。
 */
public final class IntegrationPackages {

    /** 本项目包名前缀。 */
    public static final String ROOT = "com.maccms.backend";

    /** 出站客户端所在子包。 */
    public static final String CLIENT = "integration.http";

    /** 可以直接使用出站网络 API 的子包 → 登记批次。 */
    public static final Map<String, String> RAW_API = Map.of(CLIENT, "B23");

    /**
     * 可以经出站客户端发请求的子包 → 登记批次。B28：{@code integration.urlsend}（百度推送 E09）、{@code integration.backlink}（友链回链检测 E11）。
     */
    public static final Map<String, String> REGISTERED = Map.of("integration.urlsend", "B28", "integration.backlink", "B28");

    private IntegrationPackages() {
    }

    /**
     * 相对包名（如 {@code integration.pay.alipay}）是否属于 {@code registered} 中的某个子包（本身或其下级包；
     * {@code integration.payx} 不算 {@code integration.pay}）。
     */
    public static boolean isRegistered(String modulePackage, Iterable<String> registered) {
        if (modulePackage == null) {
            return false;
        }
        for (String p : registered) {
            if (modulePackage.equals(p) || modulePackage.startsWith(p + ".")) {
                return true;
            }
        }
        return false;
    }

    /** 全限定包名转成相对包名；不在本项目包内返回 null。 */
    public static String modulePackage(String packageName) {
        if (packageName == null) {
            return null;
        }
        if (packageName.equals(ROOT)) {
            return "";
        }
        return packageName.startsWith(ROOT + ".") ? packageName.substring(ROOT.length() + 1) : null;
    }
}
