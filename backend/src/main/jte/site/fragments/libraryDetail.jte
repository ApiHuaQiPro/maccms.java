@import com.maccms.backend.site.web.view.ContentCard
@import com.maccms.backend.site.web.view.LibraryDetailPage
@import com.maccms.backend.site.web.view.MetadataItem
@import com.maccms.backend.site.web.view.NavLink
@import com.maccms.backend.site.web.view.VideoCardView
@param LibraryDetailPage page
<article class="${page.pageClass()}" data-site-page="${page.siteKind()}">
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
  <div class="content-detail__header">
    @if(page.image().isPresent())
    <div class="content-detail__image"><img class="content-card__image" src="${page.image().get().href()}" alt="${page.title()}"></div>
    @endif
    <div class="content-detail__info">
      <h1 class="content-detail__title">${page.title()}</h1>
      @if(!page.subTitle().isEmpty())
      <p class="content-detail__subtitle">${page.subTitle()}</p>
      @endif
      @if(!page.metadata().isEmpty())
      <dl class="metadata-list">
        @for(MetadataItem m : page.metadata())
        <div class="metadata-list__item">
          <dt class="metadata-list__label">${m.label()}</dt>
          <dd class="metadata-list__value">${m.value()}</dd>
        </div>
        @endfor
      </dl>
      @endif
      @if(page.external().isPresent())
      <p class="content-detail__external"><a href="${page.external().get().href()}" rel="nofollow noopener noreferrer">访问网站</a></p>
      @endif
    </div>
  </div>
  @if(!page.parts().isEmpty())
  <nav class="plot-list" aria-label="分集剧情">
    <ul class="plot-list__items">
      @for(NavLink p : page.parts())
      <li class="plot-list__item">
        @if(p.current())
        <a class="plot-list__link plot-list__link--current" href="${p.url().href()}" aria-current="page">${p.label()}</a>
        @else
        <a class="plot-list__link" href="${p.url().href()}">${p.label()}</a>
        @endif
      </li>
      @endfor
    </ul>
  </nav>
  @endif
  <section class="content-detail__section">
    @if(!page.sectionTitle().isEmpty())
    <h2 class="content-detail__section-title">${page.sectionTitle()}</h2>
    @endif
    @if(!page.sectionNote().isEmpty())
    <p class="content-detail__note">${page.sectionNote()}</p>
    @endif
    @if(!page.body().isEmptyContent())
    <div class="detail-content">${page.body()}</div>
    @elseif(!page.blurb().isEmpty())
    <div class="detail-content"><p>${page.blurb()}</p></div>
    @else
    <p class="detail-content detail-content--empty">暂无内容</p>
    @endif
  </section>
  @if(page.pager().isPresent())
  <div class="article-pager">
    @template.site.components.pagination(currentPage = page.pager().get().currentPage(), pageCount = page.pager().get().pageCount(), previousUrl = page.pager().get().previousUrl(), nextUrl = page.pager().get().nextUrl())
  </div>
  @endif
  @if(!page.screenshots().isEmpty())
  <section class="content-detail__section">
    <h2 class="content-detail__section-title">截图</h2>
    <ul class="screenshot-list">
      @for(ContentCard s : page.screenshots())
      <li class="screenshot-list__item">
        @if(s.image().isPresent())
        <img class="screenshot-list__image" src="${s.image().get().href()}" alt="${s.title()}" loading="lazy">
        @else
        <span class="screenshot-list__placeholder">${s.title()}</span>
        @endif
      </li>
      @endfor
    </ul>
  </section>
  @endif
  @if(!page.videos().isEmpty())
  <section class="content-detail__section">
    <h2 class="content-detail__section-title">相关视频</h2>
    <ul class="video-grid">
      @for(VideoCardView v : page.videos())
      <li class="video-grid__item">
        @template.site.components.videoCard(title = v.title(), remarks = v.remarks(), detailUrl = v.detailUrl(), imageUrl = v.imageUrl())
      </li>
      @endfor
    </ul>
  </section>
  @endif
  @if(!page.cards().isEmpty())
  <section class="content-detail__section">
    <h2 class="content-detail__section-title">${page.cardsTitle()}</h2>
    <ul class="content-grid topic-list">
      @for(ContentCard c : page.cards())
      <li class="content-grid__item">
        <article class="content-card">
          <a class="content-card__link" href="${c.url().href()}">
            @if(c.image().isPresent())
            <img class="content-card__image" src="${c.image().get().href()}" alt="" loading="lazy">
            @else
            <span class="content-card__placeholder" aria-hidden="true"></span>
            @endif
            <h3 class="content-card__title">${c.title()}</h3>
            @if(!c.meta().isEmpty())
            <p class="content-card__meta">${c.meta()}</p>
            @endif
          </a>
        </article>
      </li>
      @endfor
    </ul>
  </section>
  @endif
</article>
