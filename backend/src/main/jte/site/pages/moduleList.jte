@import com.maccms.backend.site.web.view.FilterChip
@import com.maccms.backend.site.web.view.FilterGroup
@import com.maccms.backend.site.web.view.HiddenField
@import com.maccms.backend.site.web.view.ModuleListView
@import com.maccms.backend.site.web.view.NavLink
@param ModuleListView page
@template.site.layout.page(chrome = page.chrome(), content = @`
<div class="${page.pageClass()}" data-site-page="${page.siteKind()}">
  @if(!page.breadcrumb().isEmpty())
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
  @endif
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
  @if(page.invalid())
  <p class="search-page__error notice notice--error" id="module-search-error">搜索条件不合法，请修改关键字或筛选条件后重新搜索。</p>
  @endif
  <form class="search-form" action="${page.formAction().href()}" method="get" role="search" aria-label="${page.formLabel()}">
    <label class="search-form__label" for="module-search-wd">关键字</label>
    @if(page.invalid())
    <input class="search-form__input" id="module-search-wd" name="wd" type="search" maxlength="64" aria-describedby="module-search-error">
    @else
    <input class="search-form__input" id="module-search-wd" name="wd" type="search" maxlength="64" value="${page.keyword()}">
    @endif
    @for(HiddenField h : page.hiddenFields())
    <input type="hidden" name="${h.name()}" value="${h.value()}">
    @endfor
    <button class="search-form__button" type="submit">搜索</button>
  </form>
  @if(page.total().isPresent())
  <p class="search-summary">共 ${page.total().get()} 条</p>
  @elseif(!page.invalid())
  <p class="search-page__hint notice">输入关键字后搜索，结果中可以继续按首字母筛选或换排序。</p>
  @endif
  @if(!page.filters().isEmpty())
  <nav class="search-filters" aria-label="筛选与排序">
    @for(FilterGroup g : page.filters())
    <div class="filter-group">
      <p class="filter-group__label" id="module-filter-${g.key()}">${g.label()}</p>
      <ul class="filter-group__list" aria-labelledby="module-filter-${g.key()}">
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
  @template.site.fragments.moduleResults(page = page)
</div>
`)
