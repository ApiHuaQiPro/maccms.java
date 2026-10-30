@import com.maccms.backend.site.web.view.UserInfoPage
@param UserInfoPage page
@template.site.layout.page(chrome = page.shell().chrome(), content = @`
<section class="member-page member-page--info" data-site-page="user-info" aria-labelledby="member-title">
  <h1 class="member-page__title" id="member-title">修改资料</h1>
  <form class="member-form" method="post" action="${page.action().href()}" data-member-form="info">
    <div class="member-form__field">
      <span class="member-form__label">用户名</span>
      <span class="member-form__value">${page.profile().userName()}</span>
    </div>
    <div class="member-form__field">
      <label class="member-form__label" for="member-info-user_nick_name">昵称</label>
      <input class="member-form__input" id="member-info-user_nick_name" name="user_nick_name" type="text" maxlength="30" value="${page.profile().nickNameInput()}">
    </div>
    <div class="member-form__field">
      <label class="member-form__label" for="member-info-user_qq">QQ</label>
      <input class="member-form__input" id="member-info-user_qq" name="user_qq" type="text" maxlength="16" value="${page.profile().qqInput()}">
    </div>
    <div class="member-form__field">
      <label class="member-form__label" for="member-info-user_question">密保问题</label>
      <input class="member-form__input" id="member-info-user_question" name="user_question" type="text" maxlength="255" value="${page.profile().questionInput()}">
    </div>
    <div class="member-form__field">
      <label class="member-form__label" for="member-info-user_answer">密保答案</label>
      <input class="member-form__input" id="member-info-user_answer" name="user_answer" type="text" maxlength="255" autocomplete="off" value="${page.profile().answerInput()}">
    </div>
    <div class="member-form__field">
      <label class="member-form__label" for="member-info-user_pwd1">新密码</label>
      <input class="member-form__input" id="member-info-user_pwd1" name="user_pwd1" type="password" maxlength="64" autocomplete="new-password" aria-describedby="member-info-pwd-hint">
      <p class="member-form__hint" id="member-info-pwd-hint">不修改密码请留空。修改后其他设备需要重新登录。</p>
    </div>
    <div class="member-form__field">
      <label class="member-form__label" for="member-info-user_pwd2">确认新密码</label>
      <input class="member-form__input" id="member-info-user_pwd2" name="user_pwd2" type="password" maxlength="64" autocomplete="new-password">
    </div>
    <div class="member-form__field">
      <label class="member-form__label" for="member-info-user_pwd">当前密码</label>
      <input class="member-form__input" id="member-info-user_pwd" name="user_pwd" type="password" maxlength="64" autocomplete="current-password" required>
    </div>
    <p class="member-form__message" role="status" aria-live="polite" data-member-message hidden></p>
    <button class="member-form__submit" type="submit">保存</button>
  </form>
  <noscript><p class="notice member-noscript">本页的表单需要启用 JavaScript。</p></noscript>
  @template.site.member.oauthLinks(links = page.oauthLinks(), binding = true)
  <ul class="member-links">
    <li><a href="${page.indexUrl().href()}" rel="nofollow">返回会员中心</a></li>
  </ul>
</section>
@template.site.member.script(page = page.shell())
`)
