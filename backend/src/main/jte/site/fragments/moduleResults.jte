@import com.maccms.backend.site.web.view.ContentCard
@import com.maccms.backend.site.web.view.ModuleListView
@param ModuleListView page
@if(page.total().isPresent())
<section class="content-list__results" aria-labelledby="module-results-title">
  <h2 class="content-list__results-title" id="module-results-title">结果</h2>
@if(page.cards().isEmpty())
  <p class="content-list__empty notice">${page.empty()}</p>
@else
  <ul class="content-grid">
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
@endif
</section>
@endif
@if(page.random() && page.total().isPresent())
<p class="search-random-notice notice">随机排序每次显示不同的结果，不提供翻页。</p>
@endif
@if(page.pagination().isPresent())
@template.site.components.pagination(currentPage = page.pagination().get().currentPage(), pageCount = page.pagination().get().pageCount(), previousUrl = page.pagination().get().previousUrl(), nextUrl = page.pagination().get().nextUrl())
@endif
