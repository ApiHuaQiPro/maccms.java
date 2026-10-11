@import com.maccms.backend.site.web.view.NavLink
@import com.maccms.backend.site.web.view.PageChrome
@import gg.jte.Content
@param PageChrome chrome
@param Content content
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${chrome.title()}</title>
  @if(chrome.noindex())
  <meta name="robots" content="noindex">
  @endif
  @if(!chrome.description().isEmpty())
  <meta name="description" content="${chrome.description()}">
  @endif
  @if(!chrome.keywords().isEmpty())
  <meta name="keywords" content="${chrome.keywords()}">
  @endif
  @if(chrome.canonical().isPresent())
  <link rel="canonical" href="${chrome.canonical().get().href()}">
  @endif
  <link rel="stylesheet" href="${chrome.stylesheet().href()}">
</head>
<body>
<a class="skip-link" href="#main">跳到主要内容</a>
<header class="site-header">
  <a class="site-brand" href="${chrome.home().href()}">
    @if(chrome.logo().isPresent())
    <img class="site-brand__logo" src="${chrome.logo().get().href()}" alt="" height="40">
    @endif
    <span class="site-brand__name">${chrome.siteName()}</span>
  </a>
  @if(!chrome.nav().isEmpty())
  <nav class="site-nav" aria-label="视频分类">
    <ul class="site-nav__list">
      @for(NavLink item : chrome.nav())
      <li class="site-nav__item">
        @if(item.current())
        <a class="site-nav__link site-nav__link--current" href="${item.url().href()}" aria-current="page">${item.label()}</a>
        @else
        <a class="site-nav__link" href="${item.url().href()}">${item.label()}</a>
        @endif
      </li>
      @endfor
    </ul>
  </nav>
  @endif
  <form class="site-search" action="${chrome.searchAction().href()}" method="get" role="search">
    <label class="site-search__label" for="site-search-wd">搜索视频</label>
    <input class="site-search__input" id="site-search-wd" name="wd" type="search" maxlength="64">
    <button class="site-search__button" type="submit">搜索</button>
  </form>
  <a class="site-login" href="${chrome.loginUrl().href()}" rel="nofollow">登录 / 会员中心</a>
</header>
<main id="main" class="site-main">
${content}
</main>
<footer class="site-footer">
  <p>${chrome.siteName()}</p>
  @if(!chrome.icp().isEmpty())
  <p class="site-footer__icp">${chrome.icp()}</p>
  @endif
</footer>
</body>
</html>
