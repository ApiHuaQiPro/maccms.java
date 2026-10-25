package com.maccms.backend.content.application;

/**
 * 当前请求的观看者会员组（B09，docs/architecture/18-b09-member-account.md 第 5.3 节）：旧版前台页面按 {@code label_user} 得到的
 * 有效会员组判定分类、详情、播放、下载与试看权限（{@code index/controller/Base.php:92-227}），{@link ContentCatalogs} 据此取组权限。
 *
 * <p>只由 {@code site} 的会员拦截器在一次页面请求内设置（{@link #enter}），请求结束时由同一拦截器关闭；没有设置时按游客组 1。
 * 公开 JSON API（{@code /api/v1/**}）与 Provide 接口从不设置，始终按游客——与旧版 api 模块不调用 {@code label_user} 相同。
 * 内容服务全部在请求线程上同步执行（没有线程池或异步调用），所以按线程保存是安全的；
 * {@code SiteContentViewerIsolationTest}（真实 Tomcat）锁定异常退出后清除、工作线程复用不串组、并发请求互不影响、公开 API 按游客。
 * 用作用域而不是给 79 处页面调用逐个加参数，理由见 18 文档第 11 节。
 */
public final class ContentViewer {

    private static final ThreadLocal<Integer> GROUP = new ThreadLocal<>();

    private ContentViewer() {
    }

    /**
     * 进入一个观看者作用域，返回的 {@link Scope} 关闭时恢复进入前的值（嵌套时逐层恢复）。
     *
     * @param groupId 有效会员组号（0～65535，{@code mac_group.group_id} 为 smallint unsigned；0 表示组不存在）
     */
    public static Scope enter(int groupId) {
        if (groupId < 0 || groupId > 65_535) {
            throw new IllegalArgumentException("会员组号越界");
        }
        Integer previous = GROUP.get();
        GROUP.set(groupId);
        return () -> {
            if (previous == null) {
                GROUP.remove();
            } else {
                GROUP.set(previous);
            }
        };
    }

    /** 当前请求的有效会员组；没有设置时为游客组。 */
    public static int groupId() {
        Integer g = GROUP.get();
        return g == null ? ContentProperties.GUEST_GROUP_ID : g;
    }

    /** 观看者作用域；{@link #close()} 不抛受检异常。 */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
