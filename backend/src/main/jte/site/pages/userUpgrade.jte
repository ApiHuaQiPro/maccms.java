@import com.maccms.backend.site.web.view.MetadataItem
@import com.maccms.backend.site.web.view.UserUpgradePage
@param UserUpgradePage page
@template.site.layout.page(chrome = page.shell().chrome(), content = @`
<section class="member-page member-page--upgrade" data-site-page="user-upgrade" aria-labelledby="member-title">
  <h1 class="member-page__title" id="member-title">升级会员</h1>
  <dl class="member-summary">
    @for(MetadataItem m : page.summary())
    <dt>${m.label()}</dt><dd>${m.value()}</dd>
    @endfor
  </dl>
  @if(page.groups().isEmpty())
  <p class="member-empty">暂无可以升级的会员组。</p>
  @else
  <form class="member-form" method="post" action="${page.action().href()}" data-member-form="upgrade" data-success-url="${page.selfUrl().href()}">
    <fieldset class="member-form__fieldset">
      <legend class="member-form__label">会员组</legend>
      @for(UserUpgradePage.Option g : page.groups())
      <label class="member-form__choice">
        <input type="radio" name="group_id" value="${g.id()}" required>
        <span>${g.name()}（日 ${g.day()}、周 ${g.week()}、月 ${g.month()}、年 ${g.year()} 积分）</span>
      </label>
      @endfor
    </fieldset>
    <fieldset class="member-form__fieldset">
      <legend class="member-form__label">时长</legend>
      <label class="member-form__choice"><input type="radio" name="long" value="day" required><span>一天</span></label>
      <label class="member-form__choice"><input type="radio" name="long" value="week"><span>一周</span></label>
      <label class="member-form__choice"><input type="radio" name="long" value="month"><span>一个月</span></label>
      <label class="member-form__choice"><input type="radio" name="long" value="year"><span>一年</span></label>
    </fieldset>
    <p class="member-form__message" role="status" aria-live="polite" data-member-message hidden></p>
    <button class="member-form__submit" type="submit">升级</button>
  </form>
  @endif
  <p class="member-page__back"><a href="${page.back().url().href()}">${page.back().label()}</a></p>
</section>
@template.site.member.script(page = page.shell())
`)
