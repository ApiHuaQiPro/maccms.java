@import com.maccms.backend.site.web.view.FilterChip
@import com.maccms.backend.site.web.view.FilterGroup
@import com.maccms.backend.site.web.view.HiddenField
@import com.maccms.backend.site.web.view.SearchPage
@import com.maccms.backend.site.web.view.VideoCardView
@param SearchPage page
@template.site.layout.page(chrome = page.chrome(), content = @`
<div class="search-page" data-site-page="search">
  <h1 class="search-page__title">${page.heading()}</h1>
  @if(page.invalid())
  <p class="search-page__error notice notice--error" id="search-page-error">搜索条件不合法，请修改关键字或筛选条件后重新搜索。</p>
  @endif
  <form class="search-form" action="${page.formAction().href()}" method="get" role="search" aria-label="搜索视频">
    <label class="search-form__label" for="search-page-wd">关键字</label>
    @if(page.invalid())
    <input class="search-form__input" id="search-page-wd" name="wd" type="search" maxlength="64" aria-describedby="search-page-error">
    @else
    <input class="search-form__input" id="search-page-wd" name="wd" type="search" maxlength="64" value="${page.keyword()}">
    @endif
    @for(HiddenField h : page.hiddenFields())
    <input type="hidden" name="${h.name()}" value="${h.value()}">
    @endfor
    <button class="search-form__button" type="submit">搜索</button>
  </form>
  @if(page.total().isPresent())
  <p class="search-summary">共 ${page.total().get()} 条</p>
  @elseif(!page.invalid())
  <p class="search-page__hint notice">输入关键字后搜索，结果中可以继续按地区、语言、年份筛选。</p>
  @endif
  @if(!page.filters().isEmpty())
  <nav class="search-filters" aria-label="筛选与排序">
    @for(FilterGroup g : page.filters())
    <div class="filter-group">
      <p class="filter-group__label" id="search-filter-${g.key()}">${g.label()}</p>
      <ul class="filter-group__list" aria-labelledby="search-filter-${g.key()}">
        @for(FilterChip c : g.chips())
        <li class="filter-group__item">
          @if(c.current())
          <a class="filter-chip filter-chip--current" href="${c.url().href()}" aria-current="true">${c.label()}</a>
          @else
          <a class="filter-chip" href="${c.url().href()}">${c.label()}</a>
          @endif
        </li>
        @endfor
      </ul>
    </div>
    @endfor
  </nav>
  @endif
  @if(page.total().isPresent())
  <section class="search-page__results" aria-labelledby="search-results-title">
    <h2 class="search-page__results-title" id="search-results-title">搜索结果</h2>
    @if(page.videos().isEmpty())
    <p class="search-empty notice">没有找到相关视频</p>
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
  @endif
  @if(page.pagination().isPresent())
  @template.site.components.pagination(currentPage = page.pagination().get().currentPage(), pageCount = page.pagination().get().pageCount(), previousUrl = page.pagination().get().previousUrl(), nextUrl = page.pagination().get().nextUrl())
  @endif
</div>
`)
