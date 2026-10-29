@import com.maccms.backend.site.web.view.UserBindPage
@param UserBindPage page
@template.site.layout.page(chrome = page.shell().chrome(), content = @`
<section class="member-page member-page--bind" data-site-page="user-bind" aria-labelledby="member-title">
  <h1 class="member-page__title" id="member-title">绑定${page.message().label()}</h1>
  <form class="member-form" method="post" action="${page.action().href()}" data-member-form="bind" data-success-url="${page.successUrl().href()}">
    @template.site.member.messageBlock(block = page.message(), prefix = "member-bind")
    <p class="member-form__message" role="status" aria-live="polite" data-member-message hidden></p>
    <button class="member-form__submit" type="submit">绑定</button>
  </form>
  <noscript><p class="notice member-noscript">本页的表单需要启用 JavaScript。</p></noscript>
  <ul class="member-links">
    <li><a href="${page.switchUrl().href()}" rel="nofollow">${page.switchText()}</a></li>
    <li><a href="${page.infoUrl().href()}" rel="nofollow">返回会员资料</a></li>
  </ul>
</section>
@template.site.member.script(page = page.shell())
`)
