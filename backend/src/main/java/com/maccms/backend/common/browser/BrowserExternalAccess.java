package com.maccms.backend.common.browser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 校验后的浏览器侧外部地址放行开关（B02，docs/architecture/11-guards-and-legacy-config.md 第 5 节）。
 * "放行"指页面可以输出让浏览器访问外部主机的地址；关闭时页面不输出任何指向外部主机的地址，按路线图第 4.2 节"关闭时的行为"处理。
 *
 * <p>按路线图第五轮（D1 已全部决定）：W1、W2 按旧版放行，默认打开；关闭状态保留给部署方选用，也用于测试中的"无外部主机"断言。
 * W3 随 B24（支付）放行，在此之前只接受 {@code false}。各分项打开后的输出在实现该访问点的批次里完成：
 * <ul>
 * <li>W2（外链图片）：B02 已在已有页面实现，打开时输出与 MACC-11 黄金向量一致；外部播放/下载地址在 B05 实现。</li>
 * <li>W1（播放器解析与外部脚本）：B05 实现；在此之前取值不影响任何页面。</li>
 * <li>W3（支付网关跳转）：B24 实现，在此之前只接受 {@code false}。</li>
 * </ul>
 * 留空（未设置）按默认值：W1、W2 打开，W3 关闭。
 *
 * @param w1 播放器解析接口、缓冲/预加载页与外部播放器脚本
 * @param w2 外链图片与外部播放/下载地址
 * @param w3 支付网关跳转
 */
public record BrowserExternalAccess(boolean w1, boolean w2, boolean w3) {

    /** 默认：W1、W2 打开，W3 关闭。 */
    public static final BrowserExternalAccess DEFAULT = new BrowserExternalAccess(true, true, false);

    /** 全部关闭（部署方可选的关闭态）。 */
    public static final BrowserExternalAccess ALL_CLOSED = new BrowserExternalAccess(false, false, false);

    /** 校验并转换；不合法时抛 {@link IllegalStateException}，信息只含配置项名称与规则。 */
    public static BrowserExternalAccess from(BrowserExternalProperties p) {
        List<String> errors = new ArrayList<>();
        boolean w1 = flag(p == null ? null : p.w1(), "maccms.browser-external.w1", DEFAULT.w1(), true, "", errors);
        boolean w2 = flag(p == null ? null : p.w2(), "maccms.browser-external.w2", DEFAULT.w2(), true, "", errors);
        boolean w3 = flag(p == null ? null : p.w3(), "maccms.browser-external.w3", DEFAULT.w3(), false,
                "W3 的放行输出在 B24 实现，当前只能为 false", errors);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("浏览器侧外部地址放行参数不合法：" + String.join("；", errors));
        }
        return new BrowserExternalAccess(w1, w2, w3);
    }

    private static boolean flag(String raw, String key, boolean unset, boolean trueAllowed, String why,
                                List<String> errors) {
        String v = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        switch (v) {
            case "" -> {
                return unset;
            }
            case "false" -> {
                return false;
            }
            case "true" -> {
                if (trueAllowed) {
                    return true;
                }
                errors.add(key + "（" + why + "）");
                return false;
            }
            default -> {
                errors.add(key + "（true 或 false）");
                return false;
            }
        }
    }
}
