@import com.maccms.backend.site.web.view.PasswordPage
@param PasswordPage page
@template.site.layout.page(chrome = page.chrome(), content = @`
<section class="password-page" data-site-page="password">
  <h1 class="password-page__title">${page.heading()}</h1>
  <p class="password-page__message" id="password-page-message">${page.message()}</p>
  @if(!page.error().isEmpty())
  <p class="password-page__error notice notice--error" id="password-page-error" role="alert">${page.error()}</p>
  @endif
  @if(page.formAction().isPresent())
  <form class="password-page__form" action="${page.formAction().get().href()}" method="post">
    <label class="password-page__label" for="password-page-pwd">访问密码</label>
    @if(page.error().isEmpty())
    <input class="password-page__input" id="password-page-pwd" name="pwd" type="password" maxlength="64" autocomplete="off" required aria-describedby="password-page-message">
    @else
    <input class="password-page__input" id="password-page-pwd" name="pwd" type="password" maxlength="64" autocomplete="off" required aria-describedby="password-page-error">
    @endif
    <button class="password-page__button" type="submit">确定</button>
  </form>
  @endif
  @if(page.back().isPresent())
  <p class="password-page__back"><a href="${page.back().get().href()}">返回</a></p>
  @endif
</section>
`)
