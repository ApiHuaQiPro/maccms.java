@import com.maccms.backend.site.web.view.DownPage
@import com.maccms.backend.site.web.view.EpisodeLink
@import com.maccms.backend.site.web.view.NavLink
@import com.maccms.backend.site.web.view.PlaySourceView
@param DownPage page
@template.site.layout.page(chrome = page.chrome(), content = @`
<article class="download-page" data-site-page="down">
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
  @for(PlaySourceView s : page.sources())
  <section class="download-list" aria-labelledby="${s.key()}-title">
    <h2 class="download-list__name" id="${s.key()}-title">${s.label()}</h2>
    @if(!s.note().isEmpty())
    <p class="download-list__note">${s.note()}</p>
    @endif
    <ul class="download-list__items">
      @for(EpisodeLink e : s.episodes())
      <li class="download-list__item">
        @if(e.current())
        <a class="download-list__link download-list__link--current" href="${e.url().href()}" aria-current="page">${e.label()}</a>
        @else
        <a class="download-list__link" href="${e.url().href()}">${e.label()}</a>
        @endif
      </li>
      @endfor
    </ul>
  </section>
  @endfor
  <p class="download-page__back"><a href="${page.detailUrl().href()}">返回详情</a></p>
</article>
`)
