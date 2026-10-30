@import com.maccms.backend.site.web.view.VerifyPage
@param VerifyPage page
@template.site.layout.page(chrome = page.chrome(), content = @`
<section class="verify-page" data-site-page="verify" aria-labelledby="verify-title">
  <h1 class="verify-page__title" id="verify-title">${page.heading()}</h1>
  <p class="verify-page__intro">请输入图中的验证码后继续。</p>
  <form class="interaction-post" method="post" action="${page.action().href()}">
    <input type="hidden" name="type" value="${page.type()}">
    <input type="hidden" name="back" value="${page.back()}">
    <div class="interaction-post__field interaction-post__field--captcha">
      <label class="interaction-post__label" for="verify-code">验证码</label>
      <input class="interaction-post__input interaction-post__input--captcha" id="verify-code" name="verify" type="text" maxlength="16" autocomplete="off" required>
      <img class="interaction-post__captcha" src="${page.captcha().src().href()}" alt="验证码" width="128" height="40">
    </div>
    <button class="interaction-post__submit" type="submit">继续</button>
  </form>
</section>
`)
