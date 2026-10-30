@import com.maccms.backend.site.web.view.BoardPostForm
@import com.maccms.backend.site.web.view.HiddenField
@param BoardPostForm form
@param String prefill
<form class="interaction-post" method="post" action="${form.action().href()}" aria-labelledby="${form.id()}-title">
  <h2 class="interaction-post__title" id="${form.id()}-title">${form.label()}</h2>
  @if(form.loginNotice().isPresent())
  <p class="interaction-post__notice notice">登录后才可以发表，<a href="${form.loginNotice().get().href()}" rel="nofollow">去登录</a>。</p>
  @endif
  @for(HiddenField h : form.fields())
  <input type="hidden" name="${h.name()}" value="${h.value()}">
  @endfor
  <div class="interaction-post__field">
    <label class="interaction-post__label" for="${form.id()}-content">内容</label>
    <textarea class="interaction-post__input" id="${form.id()}-content" name="${form.contentName()}" rows="4" maxlength="200" required>${prefill}</textarea>
  </div>
  @if(form.captcha().isPresent())
  <div class="interaction-post__field interaction-post__field--captcha">
    <label class="interaction-post__label" for="${form.id()}-verify">验证码</label>
    <input class="interaction-post__input interaction-post__input--captcha" id="${form.id()}-verify" name="verify" type="text" maxlength="16" autocomplete="off" required>
    <img class="interaction-post__captcha" src="${form.captcha().get().src().href()}" alt="验证码" width="128" height="40">
  </div>
  @endif
  <button class="interaction-post__submit" type="submit">提交</button>
</form>
