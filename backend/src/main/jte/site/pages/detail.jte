@import com.maccms.backend.site.web.view.DetailPage
@import com.maccms.backend.site.web.view.MetadataItem
@import com.maccms.backend.site.web.view.NavLink
@import com.maccms.backend.site.web.view.VideoCardView
@param DetailPage page
@template.site.layout.page(chrome = page.chrome(), content = @`
<article class="video-detail" data-site-page="detail">
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
  <div class="video-detail__header">
    <div class="video-detail__poster">
      @if(page.poster().isPresent())
      <img class="video-detail__image" src="${page.poster().get().href()}" alt="${page.title()}">
      @else
      <span class="video-detail__placeholder" aria-hidden="true"></span>
      @endif
    </div>
    <div class="video-detail__info">
      <h1 class="video-detail__title">${page.title()}</h1>
      @if(!page.subTitle().isEmpty())
      <p class="video-detail__subtitle">${page.subTitle()}</p>
      @endif
      @if(!page.metadata().isEmpty())
      <dl class="metadata-list video-detail__meta">
        @for(MetadataItem m : page.metadata())
        <div class="metadata-list__item">
          <dt class="metadata-list__label">${m.label()}</dt>
          <dd class="metadata-list__value">${m.value()}</dd>
        </div>
        @endfor
      </dl>
      @endif
    </div>
  </div>
  <section class="video-detail__section">
    <h2 class="video-detail__section-title">剧情简介</h2>
    @if(!page.body().isEmptyContent())
    <div class="detail-content">${page.body()}</div>
    @elseif(!page.blurb().isEmpty())
    <div class="detail-content"><p>${page.blurb()}</p></div>
    @else
    <p class="detail-content detail-content--empty">暂无简介</p>
    @endif
  </section>
  @if(!page.related().isEmpty())
  <section class="video-detail__section">
    <h2 class="video-detail__section-title">同类推荐</h2>
    <ul class="video-grid">
      @for(VideoCardView v : page.related())
      <li class="video-grid__item">
        @template.site.components.videoCard(title = v.title(), remarks = v.remarks(), detailUrl = v.detailUrl(), imageUrl = v.imageUrl())
      </li>
      @endfor
    </ul>
  </section>
  @endif
</article>
`)
