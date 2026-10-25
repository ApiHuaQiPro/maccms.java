package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.List;
import java.util.Optional;

/**
 * 外层播放页（旧 vod/play，B05）：严格模板策略、无脚本；播放器在同源 iframe（{@link #playerUrl}）中。本页不含任何播放地址。
 *
 * @param chrome      页面外壳
 * @param breadcrumb  首页 → 分类链 → 视频详情 → 当前集
 * @param title       h1：视频名
 * @param current     当前分组与集名，如"DPlayer-H5播放器 · 第2集"
 * @param sources     全部播放分组
 * @param previous    同一分组的上一集，可无
 * @param next        同一分组的下一集，可无
 * @param playerUrl   同源播放器内页 vod/player
 * @param playerTitle iframe 的 title
 * @param detailUrl   视频详情页
 * @param trial       是否为试看（只显示提示，计时由播放器内页的脚本负责）
 * @param trialOffer  试看时给已登录会员的购买入口（B10，19 文档第 5.3 节）；游客与非试看时为空
 */
public record PlayPage(PageChrome chrome, List<NavLink> breadcrumb, String title, String current,
                       List<PlaySourceView> sources, Optional<SafeUrl> previous, Optional<SafeUrl> next,
                       SafeUrl playerUrl, String playerTitle, SafeUrl detailUrl, boolean trial,
                       Optional<TrialOffer> trialOffer) {

    public PlayPage(PageChrome chrome, List<NavLink> breadcrumb, String title, String current,
                    List<PlaySourceView> sources, Optional<SafeUrl> previous, Optional<SafeUrl> next,
                    SafeUrl playerUrl, String playerTitle, SafeUrl detailUrl, boolean trial) {
        this(chrome, breadcrumb, title, current, sources, previous, next, playerUrl, playerTitle, detailUrl, trial,
                Optional.empty());
    }

    public PlayPage withTrialOffer(Optional<TrialOffer> offer) {
        return new PlayPage(chrome, breadcrumb, title, current, sources, previous, next, playerUrl, playerTitle,
                detailUrl, trial, offer);
    }
}
