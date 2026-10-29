@import com.maccms.backend.site.web.view.UserUnbindPage
@param UserUnbindPage page
@template.site.layout.page(chrome = page.shell().chrome(), content = @`
<section class="member-page member-page--unbind" data-site-page="user-unbind" aria-labelledby="member-title">
  <h1 class="member-page__title" id="member-title">解除绑定${page.label()}</h1>
  <form class="member-form" method="post" action="${page.action().href()}" data-member-form="unbind" data-success-url="${page.successUrl().href()}">
    <input type="hidden" name="ac" value="${page.ac()}">
    <p class="member-form__hint">解除后将不能再用该${page.label()}找回密码。</p>
    <p class="member-form__message" role="status" aria-live="polite" data-member-message hidden></p>
    <button class="member-form__submit" type="submit">解除绑定</button>
  </form>
  <noscript><p class="notice member-noscript">本页的表单需要启用 JavaScript。</p></noscript>
  <ul class="member-links">
    <li><a href="${page.infoUrl().href()}" rel="nofollow">返回会员资料</a></li>
  </ul>
</section>
@template.site.member.script(page = page.shell())
`)
