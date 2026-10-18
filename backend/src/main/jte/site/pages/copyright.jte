@import com.maccms.backend.site.web.view.CopyrightPage
@param CopyrightPage page
@template.site.layout.page(chrome = page.chrome(), content = @`
<section class="copyright-page" data-site-page="copyright">
  <h1 class="copyright-page__title">${page.heading()}</h1>
  <p class="notice copyright-notice">${page.notice()}</p>
  <p class="copyright-page__back"><a href="${page.home().href()}">返回首页</a></p>
</section>
`)
