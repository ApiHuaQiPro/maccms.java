package com.maccms.backend.site.web.view;

import com.maccms.backend.site.application.SafeUrl;
import java.util.Optional;

/**
 * 页面外壳（head、页眉、页脚）的视图模型。所有 URL 都是 {@link SafeUrl}，模板不拼接 URL。
 *
 * @param title       完整的 &lt;title&gt; 文本（页面标题 + 站名）
 * @param siteName    站名
 * @param home        站点首页的站内路径（base-url 的路径部分）
 * @param stylesheet  站点样式表的站内路径（base-url 的路径部分 + site/site.css）
 * @param logo        Logo，可无
 * @param icp         备案号文本，可为空串
 * @param description meta description，可为空串
 * @param keywords    meta keywords，可为空串
 * @param canonical   canonical 绝对地址（只由配置的 base-url 生成）；错误页没有
 * @param noindex     是否输出 robots noindex
 */
public record PageChrome(String title, String siteName, SafeUrl home, SafeUrl stylesheet, Optional<SafeUrl> logo,
                         String icp, String description, String keywords, Optional<SafeUrl> canonical, boolean noindex) {
}
