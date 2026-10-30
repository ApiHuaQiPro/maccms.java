@import com.maccms.backend.site.web.view.NavLink
@import com.maccms.backend.site.web.view.UserListPage
@param UserListPage page
@template.site.layout.page(chrome = page.shell().chrome(), content = @`
<section class="member-page member-page--list" data-site-page="${page.pageKey()}" aria-labelledby="member-title">
  <h1 class="member-page__title" id="member-title">${page.heading()}</h1>
  @for(String p : page.intro())
  <p class="member-page__intro">${p}</p>
  @endfor
  @if(!page.tabs().isEmpty())
  <nav class="member-tabs" aria-label="筛选">
    <ul class="member-tabs__list">
      @for(NavLink t : page.tabs())
      <li class="member-tabs__item">
        @if(t.current())
        <a class="member-tabs__link member-tabs__link--current" href="${t.url().href()}" aria-current="page">${t.label()}</a>
        @else
        <a class="member-tabs__link" href="${t.url().href()}">${t.label()}</a>
        @endif
      </li>
      @endfor
    </ul>
  </nav>
  @endif
  @if(page.clearAll().isPresent())
  <div class="member-page__actions">
    @template.site.member.action(action = page.clearAll().get())
  </div>
  @endif
  @template.site.member.table(table = page.table())
  <p class="member-page__back"><a href="${page.back().url().href()}">${page.back().label()}</a></p>
</section>
@template.site.member.script(page = page.shell())
`)
