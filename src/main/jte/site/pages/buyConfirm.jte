@import com.maccms.backend.site.web.view.BuyConfirmPage
@param BuyConfirmPage page
@template.site.layout.page(chrome = page.chrome(), content = @`
<section class="access-notice notice buy-confirm" data-site-page="buy-confirm">
  <h1 class="access-notice__title notice__title">${page.heading()}</h1>
  <p class="access-notice__message">${page.message()}</p>
  @if(page.form().isPresent())
  @template.site.member.contentForm(form = page.form().get(), kind = "buy")
  @endif
  @if(page.login().isPresent())
  <p class="buy-confirm__login"><a href="${page.login().get().href()}" rel="nofollow">登录会员</a></p>
  @endif
  @if(page.back().isPresent())
  <p class="access-notice__back"><a href="${page.back().get().href()}">返回</a></p>
  @endif
</section>
`)
