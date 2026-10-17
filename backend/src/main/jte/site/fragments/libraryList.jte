@import com.maccms.backend.site.web.view.ContentCard
@import com.maccms.backend.site.web.view.LibraryListPage
@import com.maccms.backend.site.web.view.NavLink
@param LibraryListPage page
<div class="${page.pageClass()}" data-site-page="${page.siteKind()}">
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
  <h1 class="content-list__title">${page.heading()}</h1>
  @if(!page.categories().isEmpty())
  <nav class="content-list__categories" aria-label="分类">
    <ul class="category-grid">
      @for(NavLink c : page.categories())
      <li class="category-grid__item">
        @if(c.current())
        <a class="category-grid__link category-grid__link--current" href="${c.url().href()}" aria-current="page">${c.label()}</a>
        @else
        <a class="category-grid__link" href="${c.url().href()}">${c.label()}</a>
        @endif
      </li>
      @endfor
    </ul>
  </nav>
  @endif
  @if(!page.intro().isEmpty())
  <p class="content-list__intro">${page.intro()}</p>
  @endif
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
          <h2 class="content-card__title">${c.title()}</h2>
          @if(!c.meta().isEmpty())
          <p class="content-card__meta">${c.meta()}</p>
          @endif
        </a>
      </article>
    </li>
    @endfor
  </ul>
  @endif
  @if(page.pagination().isPresent())
  @template.site.components.pagination(currentPage = page.pagination().get().currentPage(), pageCount = page.pagination().get().pageCount(), previousUrl = page.pagination().get().previousUrl(), nextUrl = page.pagination().get().nextUrl())
  @endif
</div>
