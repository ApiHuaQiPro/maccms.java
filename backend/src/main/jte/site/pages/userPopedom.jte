@import com.maccms.backend.site.web.view.UserPopedomPage
@param UserPopedomPage page
@template.site.layout.page(chrome = page.shell().chrome(), content = @`
<section class="member-page member-page--list" data-site-page="user-popedom" aria-labelledby="member-title">
  <h1 class="member-page__title" id="member-title">我的权限</h1>
  <p class="member-page__intro">当前会员组：${page.groupName()}</p>
  @if(page.rows().isEmpty())
  <p class="member-empty">暂无分类。</p>
  @else
  <div class="member-table-wrap">
    <table class="member-table">
      <caption class="member-table__caption">各分类的访问权限</caption>
      <thead>
        <tr>
          @for(String h : page.headers())
          <th scope="col">${h}</th>
          @endfor
        </tr>
      </thead>
      <tbody>
        @for(UserPopedomPage.Row r : page.rows())
        <tr>
          @if(r.child())
          <th scope="row" class="member-table__child">${r.name()}</th>
          @else
          <th scope="row">${r.name()}</th>
          @endif
          @for(String c : r.cells())
          <td>${c}</td>
          @endfor
        </tr>
        @endfor
      </tbody>
    </table>
  </div>
  @endif
  <p class="member-page__back"><a href="${page.back().url().href()}">${page.back().label()}</a></p>
</section>
@template.site.member.script(page = page.shell())
`)
