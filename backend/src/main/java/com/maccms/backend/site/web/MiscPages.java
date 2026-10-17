package com.maccms.backend.site.web;

import com.maccms.backend.common.web.ApiException;
import com.maccms.backend.common.web.ErrorCode;
import com.maccms.backend.content.application.ActorReadService;
import com.maccms.backend.content.application.ArticleReadService;
import com.maccms.backend.content.application.ContentReadService;
import com.maccms.backend.content.application.LegacyColumnSet;
import com.maccms.backend.content.application.LegacyRowOrder;
import com.maccms.backend.content.application.LegacyRowQuery;
import com.maccms.backend.content.application.LegacyRowReadService;
import com.maccms.backend.content.application.LegacyTable;
import com.maccms.backend.content.application.VideoListQuery;
import com.maccms.backend.content.application.VideoSort;
import com.maccms.backend.content.application.WebsiteReadService;
import com.maccms.backend.content.application.view.CategoryDto;
import com.maccms.backend.content.application.view.LegacyRowDto;
import com.maccms.backend.content.application.view.VideoSummaryDto;
import com.maccms.backend.site.application.LegacyPage;
import com.maccms.backend.site.application.LegacyTextEntities;
import com.maccms.backend.site.application.LegacyUrlBuilder;
import com.maccms.backend.site.application.SafeUrl;
import com.maccms.backend.site.application.SiteFeeds;
import com.maccms.backend.site.application.SiteMiscSettings;
import com.maccms.backend.site.application.SiteRoute;
import com.maccms.backend.site.application.SiteSettings;
import com.maccms.backend.site.web.view.CopyrightPage;
import com.maccms.backend.site.web.view.HelpPage;
import com.maccms.backend.site.web.view.MapPage;
import com.maccms.backend.site.web.view.NavLink;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * B06 杂项页的视图模型（docs/architecture/16-b06-misc-pages-provide.md 第 4.1–4.5 节）：地图、帮助、版权提示页、站点级 RSS 与
 * 搜索引擎地图。数据只来自 content 的已发布服务（可见分类、已审核视频），与 B05 页面的可见性相同。
 */
public class MiscPages {

    private final SitePages pages;
    private final SiteSettings site;
    private final LegacyUrlBuilder urls;
    private final ContentReadService videos;
    private final ArticleReadService articles;
    private final ActorReadService actors;
    private final WebsiteReadService websites;
    private final LegacyRowReadService rows;
    private final SiteMiscSettings misc;

    public MiscPages(SitePages pages, LegacyUrlBuilder urls, ContentReadService videos, ArticleReadService articles,
                     ActorReadService actors, WebsiteReadService websites, LegacyRowReadService rows,
                     SiteMiscSettings misc) {
        this.pages = pages;
        this.site = pages.site();
        this.urls = urls;
        this.videos = videos;
        this.articles = articles;
        this.actors = actors;
        this.websites = websites;
        this.rows = rows;
        this.misc = misc;
    }

    /** 地图（旧 map/index）：各模型的可见分类树与站内入口。 */
    public MapPage map() {
        List<CategoryDto> vodRoots = videos.listCategories().items();
        List<MapPage.Section> sections = new ArrayList<>();
        section(sections, "map-vod", "视频分类", vodRoots, id -> urls.vodType(id, 1));
        section(sections, "map-art", "文章分类", articles.listCategories().items(),
                id -> urls.legacy(SiteRoute.Legacy.paged(LegacyPage.ART_TYPE, id, 1)));
        section(sections, "map-actor", "演员分类", actors.listCategories().items(),
                id -> urls.legacy(SiteRoute.Legacy.paged(LegacyPage.ACTOR_TYPE, id, 1)));
        section(sections, "map-website", "网址分类", websites.listCategories().items(),
                id -> urls.legacy(SiteRoute.Legacy.paged(LegacyPage.WEBSITE_TYPE, id, 1)));
        List<NavLink> links = new ArrayList<>();
        links.add(link("专题", LegacyPage.TOPIC_INDEX));
        links.add(link("演员", LegacyPage.ACTOR_INDEX));
        links.add(link("角色", LegacyPage.ROLE_INDEX));
        links.add(link("网址", LegacyPage.WEBSITE_INDEX));
        for (Map.Entry<LegacyPage, String> f : FEEDS.entrySet()) {
            links.add(link(f.getValue(), f.getKey()));
        }
        return new MapPage(pages.content("网站地图 - " + site.name(), "", "", canonical(LegacyPage.MAP_INDEX), nav(vodRoots)),
                "网站地图", sections, links);
    }

    /** 帮助（旧 help/index）：固定结构，不回显参数。 */
    public HelpPage help() {
        List<NavLink> links = List.of(
                new NavLink("视频搜索", site.publicPath(urls.vodSearch(Map.of(), 1)), false),
                link("网站地图", LegacyPage.MAP_INDEX),
                link("RSS 订阅", LegacyPage.RSS_INDEX));
        return new HelpPage(pages.content("帮助 - " + site.name(), "", "", canonical(LegacyPage.HELP_INDEX),
                nav(videos.listCategories().items())), site.name(), links);
    }

    /**
     * 版权提示页（旧 vod/copyright，Vod.php:74-78）：与详情页相同的存在/审核/分类可见/详情权限判定（不存在 → 404），不看 vod_copyright。
     * 视频需要访问密码或详情因版权不展示时（详情服务给出 403）仍显示提示页，但标题为固定文案，不输出视频名。
     */
    public CopyrightPage copyright(long vodId) {
        String heading;
        try {
            heading = LegacyTextEntities.decodeOnce(videos.getVideo(vodId).name());
        } catch (ApiException e) {
            if (e.code() != ErrorCode.VIDEO_PASSWORD_REQUIRED && e.code() != ErrorCode.VIDEO_COPYRIGHT_RESTRICTED) {
                throw e;
            }
            heading = "版权提示";
        }
        SafeUrl canonical = site.canonical(urls.legacy(SiteRoute.Legacy.of(LegacyPage.VOD_COPYRIGHT, vodId)));
        return new CopyrightPage(pages.content(heading + " - " + site.name(), "", "", canonical,
                nav(videos.listCategories().items())), heading, misc.copyrightNotice(), site.homePath());
    }

    /** 站点级 RSS（旧 rss/index）：最近更新的 {@value SiteFeeds#RSS_ITEMS} 个视频。 */
    public String rss() {
        List<SiteFeeds.Item> items = new ArrayList<>();
        for (VideoSummaryDto v : videos.listVideos(new VideoListQuery(null, VideoSort.TIME, true, 1,
                SiteFeeds.RSS_ITEMS)).items()) {
            String description = LegacyTextEntities.decodeOnce(v.blurb()).strip();
            if (description.isEmpty()) {
                description = LegacyTextEntities.decodeOnce(v.remarks()).strip();
            }
            items.add(new SiteFeeds.Item(LegacyTextEntities.decodeOnce(v.name()), site.canonical(urls.vodDetail(v.id())),
                    description, v.updatedAt()));
        }
        return SiteFeeds.rss(site.name(), site.canonical(urls.home()), site.description(), items);
    }

    /** 搜索引擎地图（旧 rss/baidu、google、so、sogou、bing、sm）：首页 + 最近更新的 {@value SiteFeeds#SITEMAP_ITEMS} 个视频详情页。 */
    public String sitemap() {
        List<SiteFeeds.Entry> entries = new ArrayList<>();
        for (LegacyRowDto r : rows.read(LegacyRowQuery.of(LegacyTable.VOD, LegacyColumnSet.LIST, LegacyRowOrder.TIME_DESC,
                0, SiteFeeds.SITEMAP_ITEMS)).rows()) {
            entries.add(new SiteFeeds.Entry(site.canonical(urls.vodDetail(r.number("vod_id"))), r.number("vod_time")));
        }
        return SiteFeeds.sitemap(site.canonical(urls.home()), entries);
    }

    /** 地图页中 RSS 各格式的链接文字（顺序固定）。 */
    static final Map<LegacyPage, String> FEEDS = feeds();

    private static Map<LegacyPage, String> feeds() {
        Map<LegacyPage, String> m = new java.util.LinkedHashMap<>();
        m.put(LegacyPage.RSS_INDEX, "RSS");
        m.put(LegacyPage.RSS_BAIDU, "百度地图");
        m.put(LegacyPage.RSS_GOOGLE, "Google 地图");
        m.put(LegacyPage.RSS_SO, "360 地图");
        m.put(LegacyPage.RSS_SOGOU, "搜狗地图");
        m.put(LegacyPage.RSS_BING, "必应地图");
        m.put(LegacyPage.RSS_SM, "神马地图");
        return java.util.Collections.unmodifiableMap(m);
    }

    private void section(List<MapPage.Section> out, String id, String title, List<CategoryDto> roots,
                         IntFunction<String> path) {
        if (!roots.isEmpty()) {
            out.add(new MapPage.Section(id, title, nodes(roots, path)));
        }
    }

    private List<MapPage.Node> nodes(List<CategoryDto> categories, IntFunction<String> path) {
        List<MapPage.Node> out = new ArrayList<>();
        for (CategoryDto c : categories) {
            out.add(new MapPage.Node(new NavLink(LegacyTextEntities.decodeOnce(c.name()), site.publicPath(path.apply(c.id())),
                    false), nodes(c.children(), path)));
        }
        return out;
    }

    private NavLink link(String label, LegacyPage page) {
        return new NavLink(label, site.publicPath(urls.legacy(SiteRoute.Legacy.paged(page, 0, 1))), false);
    }

    private SafeUrl canonical(LegacyPage page) {
        return site.canonical(urls.legacy(SiteRoute.Legacy.of(page, 0)));
    }

    /** 页眉的顶级视频分类导航（与三页相同）。 */
    private List<NavLink> nav(List<CategoryDto> roots) {
        List<NavLink> out = new ArrayList<>();
        for (CategoryDto c : roots) {
            out.add(new NavLink(LegacyTextEntities.decodeOnce(c.name()), site.publicPath(urls.vodType(c.id(), 1)), false));
        }
        return out;
    }
}
