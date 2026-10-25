@import com.maccms.backend.site.web.view.GbookPage
@param GbookPage page
@template.site.layout.page(chrome = page.chrome(), content = @`
<section class="gbook-page gbook-page--report" data-site-page="gbook-report" aria-labelledby="gbook-title">
  <h1 class="gbook-page__title" id="gbook-title">${page.heading()}</h1>
  @if(!page.subject().isEmpty())
  <p class="gbook-page__subject">报错内容：${page.subject()}</p>
  @endif
  @if(page.form().isPresent())
  @template.site.interaction.postForm(form = page.form().get(), prefill = page.subject())
  @else
  <p class="notice">留言功能暂未开放。</p>
  @endif
</section>
`)
