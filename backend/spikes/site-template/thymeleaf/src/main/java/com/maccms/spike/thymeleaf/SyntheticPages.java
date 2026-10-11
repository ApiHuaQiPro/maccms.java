package com.maccms.spike.thymeleaf;

import com.maccms.spike.thymeleaf.model.NavItem;
import com.maccms.spike.thymeleaf.model.PageMeta;
import com.maccms.spike.thymeleaf.model.ShellPage;
import com.maccms.spike.thymeleaf.model.SiteSettings;
import com.maccms.spike.thymeleaf.model.VideoCard;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 全部为合成数据：不连数据库，不读取任何配置文件或环境变量。 */
public final class SyntheticPages {

    public static final SiteSettings SITE = new SiteSettings("示例影视站（合成）", "http://site.local.test/");
    public static final int LARGE_FILLER = 400;
    public static final String PARTIAL_MARKER = "PARTIAL-CONTENT-MARKER";

    private static final List<NavItem> NAV = List.of(
            new NavItem("电影", "http://site.local.test/index.php/vod/type/id/1.html"),
            new NavItem("连续剧", "http://site.local.test/index.php/vod/type/id/2.html"));

    private SyntheticPages() {
    }

    public static Optional<ShellPage> byName(String name) {
        return Optional.ofNullable(switch (name) {
            case "normal" -> normal();
            case "xss" -> malicious();
            case "empty" -> empty();
            case "boom-small" -> boom(0);
            case "boom-large" -> boom(LARGE_FILLER);
            default -> null;
        });
    }

    /** 合法普通文本：中文、全角标点、以及必须按字面显示的 &、<、> 与引号。 */
    public static ShellPage normal() {
        return new ShellPage(SITE, NAV,
                new PageMeta("最新更新", "《三体》与“红岸”基地 & 更多", "http://site.local.test/"),
                "最新更新",
                List.of(card(1, "流浪地球 2：第一季 & 特别篇 <预告>", "更新至第 12 集"),
                        card(2, "《三体》“红岸”篇", "HD"),
                        card(3, "Tom & Jerry's \"Cat\"", "已完结")));
    }

    /** HTML 文本、属性、URL、标题等上下文的恶意字符串。 */
    public static ShellPage malicious() {
        return new ShellPage(new SiteSettings("'><svg onload=alert(6)>", "http://site.local.test/"), NAV,
                new PageMeta("</title><script>alert(4)</script>", "\" onload=\"alert(5)", "http://site.local.test/"),
                "<script>alert('h1')</script>",
                List.of(new VideoCard("\"><img src=x onerror=alert(1)>", "javascript:alert(7)",
                        "x\" onerror=\"alert(2)", "</li><script>alert(3)</script>")));
    }

    public static ShellPage empty() {
        return new ShellPage(SITE, NAV, new PageMeta("空分类", "", "http://site.local.test/"), "空分类", List.of());
    }

    /** filler 张正常卡片之后放一张会在渲染时抛异常的卡片。 */
    public static ShellPage boom(int filler) {
        List<VideoCard> cards = new ArrayList<>();
        for (int i = 1; i <= filler; i++) {
            cards.add(card(i, "填充影片 " + i, "第 " + i + " 集"));
        }
        cards.add(new VideoCard(VideoCard.BOOM, "http://site.local.test/x.html", "/upload/x.jpg", "boom"));
        return new ShellPage(SITE, NAV, new PageMeta("渲染失败实验", "", "http://site.local.test/"),
                PARTIAL_MARKER, cards);
    }

    private static VideoCard card(int id, String name, String remarks) {
        return new VideoCard(name, "http://site.local.test/index.php/vod/detail/id/" + id + ".html",
                "/upload/vod/" + id + ".jpg", remarks);
    }
}
