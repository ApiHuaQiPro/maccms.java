@import com.maccms.backend.site.web.view.FilterChip
@import com.maccms.backend.site.web.view.FilterGroup
@import com.maccms.backend.site.web.view.HiddenField
@import com.maccms.backend.site.web.view.SearchPage
@param SearchPage page
@template.site.layout.page(chrome = page.chrome(), content = @`
<div class="search-page" data-site-page="${page.siteKind()}">
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
  @template.site.fragments.searchResults(page = page)
</div>
`)
