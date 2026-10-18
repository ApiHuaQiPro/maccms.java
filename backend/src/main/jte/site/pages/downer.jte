@import com.maccms.backend.site.web.view.DownerPage
@import com.maccms.backend.site.web.view.NavLink
@param DownerPage page
@template.site.layout.page(chrome = page.chrome(), content = @`
<article class="download-page" data-site-page="downer">
  <nav class="breadcrumb" aria-label="当前位置">
    <ol class="breadcrumb__list">
      @for(NavLink b : page.breadcrumb())
      <li class="breadcrumb__item">
        @if(b.current())
        <span class="breadcrumb__current" aria-current="page">${b.label()}</span>
        @else
        <a class="breadcrumb__link" href="${b.url().href()}">${b.label()}</a>
        @endif
      </li>
      @endfor
    </ol>
  </nav>
  <h1 class="download-page__title">${page.title()}</h1>
  <p class="download-page__episode">${page.episode()}</p>
  @if(!page.notice().isEmpty())
  <div class="access-notice notice">
    <p class="access-notice__message">${page.notice()}</p>
  </div>
  @elseif(page.link().isPresent())
  <p class="download-page__address"><a class="download-page__link" href="${page.link().get().href()}" rel="nofollow noopener noreferrer">下载地址</a></p>
  @else
  <p class="download-page__address">下载地址：<code class="download-page__text">${page.text()}</code></p>
  @endif
  <p class="download-page__back"><a href="${page.downUrl().href()}">返回下载列表</a></p>
</article>
`)
