@import com.maccms.backend.site.web.view.UserFindpassPage
@param UserFindpassPage page
@template.site.layout.page(chrome = page.shell().chrome(), content = @`
<section class="member-page member-page--findpass" data-site-page="user-findpass" aria-labelledby="member-title">
  <h1 class="member-page__title" id="member-title">找回密码</h1>
  <form class="member-form" method="post" action="${page.action().href()}" data-member-form="findpass" data-success-url="${page.successUrl().href()}">
    <div class="member-form__field">
      <label class="member-form__label" for="member-findpass-user_name">用户名</label>
      <input class="member-form__input" id="member-findpass-user_name" name="user_name" type="text" maxlength="30" autocomplete="username" required>
    </div>
    <div class="member-form__field">
      <label class="member-form__label" for="member-findpass-user_question">密保问题</label>
      <input class="member-form__input" id="member-findpass-user_question" name="user_question" type="text" maxlength="255" required>
    </div>
    <div class="member-form__field">
      <label class="member-form__label" for="member-findpass-user_answer">密保答案</label>
      <input class="member-form__input" id="member-findpass-user_answer" name="user_answer" type="text" maxlength="255" autocomplete="off" required>
    </div>
    <div class="member-form__field">
      <label class="member-form__label" for="member-findpass-user_pwd">新密码</label>
      <input class="member-form__input" id="member-findpass-user_pwd" name="user_pwd" type="password" maxlength="64" autocomplete="new-password" required>
    </div>
    <div class="member-form__field">
      <label class="member-form__label" for="member-findpass-user_pwd2">确认新密码</label>
      <input class="member-form__input" id="member-findpass-user_pwd2" name="user_pwd2" type="password" maxlength="64" autocomplete="new-password" required>
    </div>
    @template.site.member.captcha(captcha = page.captcha(), id = "member-findpass-verify")
    <p class="member-form__message" role="status" aria-live="polite" data-member-message hidden></p>
    <button class="member-form__submit" type="submit">重置密码</button>
  </form>
  <noscript><p class="notice member-noscript">本页的表单需要启用 JavaScript。</p></noscript>
  <ul class="member-links">
    <li><a href="${page.emailUrl().href()}" rel="nofollow">通过邮箱找回</a></li>
    <li><a href="${page.phoneUrl().href()}" rel="nofollow">通过手机找回</a></li>
    <li><a href="${page.loginUrl().href()}" rel="nofollow">返回登录</a></li>
  </ul>
</section>
@template.site.member.script(page = page.shell())
`)
