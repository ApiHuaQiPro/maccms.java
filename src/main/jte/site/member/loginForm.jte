@import com.maccms.backend.site.web.view.UserLoginForm
@param UserLoginForm form
@param String prefix
<form class="member-form" method="post" action="${form.action().href()}" data-member-form="login" data-success-url="${form.successUrl().href()}">
  <div class="member-form__field">
    <label class="member-form__label" for="${prefix}-user_name">用户名或邮箱</label>
    <input class="member-form__input" id="${prefix}-user_name" name="user_name" type="text" maxlength="64" autocomplete="username" required>
  </div>
  <div class="member-form__field">
    <label class="member-form__label" for="${prefix}-user_pwd">密码</label>
    <input class="member-form__input" id="${prefix}-user_pwd" name="user_pwd" type="password" maxlength="64" autocomplete="current-password" required>
  </div>
  @if(form.captcha().isPresent())
  @template.site.member.captcha(captcha = form.captcha().get(), id = prefix + "-verify")
  @endif
  <p class="member-form__message" role="status" aria-live="polite" data-member-message hidden></p>
  <button class="member-form__submit" type="submit">登录</button>
</form>
@template.site.member.oauthLinks(links = form.oauthLinks(), binding = false)
<ul class="member-links">
  @if(form.regUrl().isPresent())
  <li><a href="${form.regUrl().get().href()}" rel="nofollow">注册</a></li>
  @endif
  <li><a href="${form.findpassUrl().href()}" rel="nofollow">找回密码</a></li>
</ul>
