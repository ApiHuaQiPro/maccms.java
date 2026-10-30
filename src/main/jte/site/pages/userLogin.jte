@import com.maccms.backend.site.web.view.UserLoginPage
@param UserLoginPage page
@template.site.layout.page(chrome = page.shell().chrome(), content = @`
<section class="member-page member-page--login" data-site-page="user-login" aria-labelledby="member-title">
  <h1 class="member-page__title" id="member-title">会员登录</h1>
  @template.site.member.loginForm(form = page.form(), prefix = "member-login")
  <noscript><p class="notice member-noscript">本页的表单需要启用 JavaScript。</p></noscript>
</section>
@template.site.member.script(page = page.shell())
`)
