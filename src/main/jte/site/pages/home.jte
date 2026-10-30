@import com.maccms.backend.site.web.view.HomePage
@import com.maccms.backend.site.web.view.NavLink
@import com.maccms.backend.site.web.view.VideoCardView
@import com.maccms.backend.site.web.view.VideoSection
@param HomePage page
@template.site.layout.page(chrome = page.chrome(), content = @`
<div class="site-home" data-site-page="home">
  <h1 class="site-home__title">${page.heading()}</h1>
  @if(!page.categories().isEmpty())
  <nav class="site-home__categories" aria-label="全部分类">
    <ul class="category-grid">
      @for(NavLink c : page.categories())
      <li class="category-grid__item"><a class="category-grid__link" href="${c.url().href()}">${c.label()}</a></li>
      @endfor
    </ul>
  </nav>
  @endif
  @if(page.sections().isEmpty())
  <p class="site-home__empty notice">暂无内容</p>
  @else
  @for(VideoSection s : page.sections())
  <section class="site-home__section">
    <div class="site-home__section-header">
      <h2 class="site-home__section-title">${s.heading()}</h2>
      @if(s.moreUrl().isPresent())
      <a class="site-home__more" href="${s.moreUrl().get().href()}" aria-label="更多${s.heading()}">更多</a>
      @endif
    </div>
    <ul class="video-grid">
      @for(VideoCardView v : s.videos())
      <li class="video-grid__item">
        @template.site.components.videoCard(title = v.title(), remarks = v.remarks(), detailUrl = v.detailUrl(), imageUrl = v.imageUrl())
      </li>
      @endfor
    </ul>
  </section>
  @endfor
  @endif
</div>
`)
