@import com.maccms.backend.site.web.view.UserFindpassMsgPage
@param UserFindpassMsgPage page
@template.site.layout.page(chrome = page.shell().chrome(), content = @`
<section class="member-page member-page--findpass-msg" data-site-page="user-findpass-msg" aria-labelledby="member-title">
  <h1 class="member-page__title" id="member-title">通过${page.message().label()}找回密码</h1>
  <form class="member-form" method="post" action="${page.resetAction().href()}" data-member-form="findpass_reset" data-success-url="${page.loginUrl().href()}">
    @template.site.member.messageBlock(block = page.message(), prefix = "member-findpass-msg")
    <div class="member-form__field">
      <label class="member-form__label" for="member-findpass-msg-user_pwd">新密码</label>
      <input class="member-form__input" id="member-findpass-msg-user_pwd" name="user_pwd" type="password" minlength="6" maxlength="64" autocomplete="new-password" required>
    </div>
    <div class="member-form__field">
      <label class="member-form__label" for="member-findpass-msg-user_pwd2">确认新密码</label>
      <input class="member-form__input" id="member-findpass-msg-user_pwd2" name="user_pwd2" type="password" minlength="6" maxlength="64" autocomplete="new-password" required>
    </div>
    <p class="member-form__message" role="status" aria-live="polite" data-member-message hidden></p>
    <button class="member-form__submit" type="submit">重置密码</button>
  </form>
  <noscript><p class="notice member-noscript">本页的表单需要启用 JavaScript。</p></noscript>
  <ul class="member-links">
    <li><a href="${page.loginUrl().href()}" rel="nofollow">返回登录</a></li>
  </ul>
</section>
@template.site.member.script(page = page.shell())
`)
