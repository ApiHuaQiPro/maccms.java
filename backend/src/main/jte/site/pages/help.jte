@import com.maccms.backend.site.web.view.HelpPage
@import com.maccms.backend.site.web.view.NavLink
@param HelpPage page
@template.site.layout.page(chrome = page.chrome(), content = @`
<section class="help-page" data-site-page="help">
  <h1 class="help-page__title">帮助</h1>
  <div class="detail-content">
    <p>欢迎访问${page.siteName()}。可以按分类浏览，也可以在页面顶部的搜索框中按片名查找。</p>
    <p>常用入口：</p>
    <ul class="help-page__links">
      @for(NavLink link : page.links())
      <li><a href="${link.url().href()}">${link.label()}</a></li>
      @endfor
    </ul>
  </div>
</section>
`)
