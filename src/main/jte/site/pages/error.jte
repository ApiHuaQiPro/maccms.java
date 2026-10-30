@import com.maccms.backend.site.web.view.ErrorPage
@param ErrorPage page
@template.site.layout.page(chrome = page.chrome(), content = @`
<section class="notice notice--error" data-site-page="error">
  <h1 class="notice__title">${page.heading()}</h1>
  <p>${page.message()}</p>
  <p><a href="${page.chrome().home().href()}">返回首页</a></p>
</section>
`)
