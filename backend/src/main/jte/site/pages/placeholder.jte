@import com.maccms.backend.site.web.view.PlaceholderPage
@param PlaceholderPage page
@template.site.layout.page(chrome = page.chrome(), content = @`
<section class="notice" data-site-page="placeholder">
  <h1 class="notice__title">站点骨架占位页</h1>
  <p>本页只用于验收 site 模块骨架（模板渲染、站点配置、错误页），不是首页、分类页或详情页。</p>
</section>
`)
