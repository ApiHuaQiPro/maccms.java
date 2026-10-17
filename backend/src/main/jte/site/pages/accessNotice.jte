@import com.maccms.backend.site.web.view.AccessNoticePage
@param AccessNoticePage page
@template.site.layout.page(chrome = page.chrome(), content = @`
<section class="access-notice notice" data-site-page="access-notice">
  <h1 class="access-notice__title notice__title">${page.heading()}</h1>
  <p class="access-notice__message">${page.message()}</p>
  @if(page.back().isPresent())
  <p class="access-notice__back"><a href="${page.back().get().href()}">返回</a></p>
  @endif
</section>
`)
