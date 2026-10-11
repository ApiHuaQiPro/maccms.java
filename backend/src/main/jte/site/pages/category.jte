@import com.maccms.backend.site.web.view.CategoryPage
@import com.maccms.backend.site.web.view.NavLink
@import com.maccms.backend.site.web.view.VideoCardView
@param CategoryPage page
@template.site.layout.page(chrome = page.chrome(), content = @`
<div class="category-page" data-site-page="category">
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
  <h1 class="category-page__title">${page.heading()}</h1>
  @if(!page.subcategories().isEmpty())
  <nav class="category-page__children" aria-label="子分类">
    <ul class="category-grid">
      @for(NavLink c : page.subcategories())
      <li class="category-grid__item"><a class="category-grid__link" href="${c.url().href()}">${c.label()}</a></li>
      @endfor
    </ul>
  </nav>
  @endif
  <section class="category-page__videos">
    <h2 class="category-page__list-title">视频列表</h2>
    @if(page.videos().isEmpty())
    <p class="category-page__empty notice">该分类暂无内容</p>
    @else
    <ul class="video-grid">
      @for(VideoCardView v : page.videos())
      <li class="video-grid__item">
        @template.site.components.videoCard(title = v.title(), remarks = v.remarks(), detailUrl = v.detailUrl(), imageUrl = v.imageUrl())
      </li>
      @endfor
    </ul>
    @endif
  </section>
  @if(page.pagination().isPresent())
  @template.site.components.pagination(currentPage = page.pagination().get().currentPage(), pageCount = page.pagination().get().pageCount(), previousUrl = page.pagination().get().previousUrl(), nextUrl = page.pagination().get().nextUrl())
  @endif
</div>
`)
