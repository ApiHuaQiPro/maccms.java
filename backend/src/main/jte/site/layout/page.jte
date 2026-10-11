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
