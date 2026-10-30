package com.maccms.backend.site.application;

/**
 * 静态生成的页面来源（B16，docs/architecture/25-b16-static-timming.md 第 4 节）：以游客身份对前台的动态地址做一次进程内 GET，返回与
 * 浏览器访问该地址时完全相同的正文。前台未启用时没有这个 Bean。
 *
 * <p>实现（{@code site.web.InternalPageRenderer}）按 {@link LegacyUrlBuilder} 生成动态地址，经 Spring MVC 的处理器映射、拦截器与控制器
 * 处理，不经过网络、不读写文件，不带任何 cookie 与会话。
 */
public interface StaticPageSource {

    /** 要生成的页面（动态地址由实现按当前 URL 形态生成）。 */
    sealed interface Target {
    }

    /** 首页；{@code wap} 为真时按手机模板（旧 {@code make/index?ac2=wap}）。 */
    record Home(boolean wap) implements Target {
    }

    /** 地图 {@code map/index}。 */
    record Sitemap() implements Target {
    }

    /** 站点级 RSS 与搜索引擎地图 {@code rss/<name>}（index、baidu、google、so、sogou、bing、sm）。 */
    record Rss(String name) implements Target {
    }

    /** 自定义页 {@code label/<name>}（name 不含扩展名）。 */
    record Label(String name) implements Target {
    }

    /** 视频分类页 {@code vod/type}。 */
    record VodType(int typeId, int page) implements Target {
    }

    /** 文章分类页 {@code art/type}。 */
    record ArtType(int typeId, int page) implements Target {
    }

    /** 视频详情页 {@code vod/detail}。 */
    record VodDetail(long vodId) implements Target {
    }

    /** 播放页 {@code vod/play}（{@code down} 为真时下载页 {@code vod/down}）。 */
    record VodEpisode(boolean down, long vodId, int sid, int nid) implements Target {
    }

    /** 文章详情页 {@code art/detail}（分页）。 */
    record ArtDetail(long artId, int page) implements Target {
    }

    /** 专题首页 {@code topic/index}。 */
    record TopicIndex(int page) implements Target {
    }

    /** 专题详情页 {@code topic/detail}。 */
    record TopicDetail(long topicId) implements Target {
    }

    /**
     * 结果。
     *
     * @param status 处理结果的 HTTP 状态（页面不存在 404、关站 503 等；控制器抛出的异常按其状态，未知异常 500）
     * @param body   正文字节（只有 200 时有意义）
     */
    record Rendered(int status, byte[] body) {
        public boolean ok() {
            return status == 200;
        }
    }

    Rendered render(Target target);
}
