@import com.maccms.backend.site.web.view.UserRegPage
@param UserRegPage page
@template.site.layout.page(chrome = page.shell().chrome(), content = @`
<section class="member-page member-page--reg" data-site-page="user-reg" aria-labelledby="member-title">
  <h1 class="member-page__title" id="member-title">会员注册</h1>
  @if(page.open())
  <form class="member-form" method="post" action="${page.action().href()}" data-member-form="reg" data-success-url="${page.successUrl().href()}">
    <div class="member-form__field">
      <label class="member-form__label" for="member-reg-user_name">用户名</label>
      <input class="member-form__input" id="member-reg-user_name" name="user_name" type="text" minlength="6" maxlength="30" autocomplete="username" required aria-describedby="member-reg-user_name-hint">
      <p class="member-form__hint" id="member-reg-user_name-hint">只能包含字母和数字，至少 6 个字符。</p>
    </div>
    <div class="member-form__field">
      <label class="member-form__label" for="member-reg-user_pwd">密码</label>
      <input class="member-form__input" id="member-reg-user_pwd" name="user_pwd" type="password" maxlength="64" autocomplete="new-password" required>
    </div>
    <div class="member-form__field">
      <label class="member-form__label" for="member-reg-user_pwd2">确认密码</label>
      <input class="member-form__input" id="member-reg-user_pwd2" name="user_pwd2" type="password" maxlength="64" autocomplete="new-password" required>
    </div>
    @if(page.message().isPresent())
    @template.site.member.messageBlock(block = page.message().get(), prefix = "member-reg")
    @endif
    @if(page.captcha().isPresent())
    @template.site.member.captcha(captcha = page.captcha().get(), id = "member-reg-verify")
    @endif
    <p class="member-form__message" role="status" aria-live="polite" data-member-message hidden></p>
    <button class="member-form__submit" type="submit">注册</button>
  </form>
  <noscript><p class="notice member-noscript">本页的表单需要启用 JavaScript。</p></noscript>
  @else
  <p class="notice">未开放注册</p>
  @endif
  <ul class="member-links">
    <li><a href="${page.loginUrl().href()}" rel="nofollow">已有账号，去登录</a></li>
  </ul>
</section>
@template.site.member.script(page = page.shell())
`)
