@import com.maccms.spike.jte.model.NavItem
@import com.maccms.spike.jte.model.PageMeta
@import com.maccms.spike.jte.model.SiteSettings
@import gg.jte.Content
@import java.util.List
@param PageMeta meta
@param SiteSettings site
@param List<NavItem> nav
@param Content content
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${meta.title()} - ${site.name()}</title>
  <meta name="description" content="${meta.description()}">
  <link rel="canonical" href="${meta.canonical()}">
</head>
<body>
<header class="site-header">
  <a class="skip-link" href="#main">跳到主要内容</a>
  <a class="site-name" href="${site.baseUrl()}">${site.name()}</a>
  <nav aria-label="分类导航">
    <ul>
      @for(NavItem item : nav)
      <li><a href="${item.url()}">${item.name()}</a></li>
      @endfor
    </ul>
  </nav>
</header>
${content}
<footer class="site-footer">
  <p>${site.name()}</p>
</footer>
</body>
</html>
