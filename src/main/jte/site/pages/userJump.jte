@import com.maccms.backend.site.web.view.UserJumpPage
@param UserJumpPage page
@template.site.layout.page(chrome = page.chrome(), content = @`
<section class="jump-page" data-site-page="user-jump">
  @if(page.success())
  <p class="jump-page__message notice" role="status">${page.message()}</p>
  @else
  <p class="jump-page__message notice notice--error" role="alert">${page.message()}</p>
  @endif
  @if(page.url().isPresent())
  <p><a class="jump-page__link" href="${page.url().get().href()}">继续</a></p>
  @endif
</section>
`)
