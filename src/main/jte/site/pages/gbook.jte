@import com.maccms.backend.site.web.view.GbookPage
@param GbookPage page
@template.site.layout.page(chrome = page.chrome(), content = @`
<section class="gbook-page" data-site-page="gbook" aria-labelledby="gbook-title">
  <h1 class="gbook-page__title" id="gbook-title">${page.heading()}</h1>
  @if(page.form().isPresent())
  @template.site.interaction.postForm(form = page.form().get(), prefill = "")
  @endif
  <p class="gbook-page__total">共 ${page.list().total()} 条留言</p>
  @template.site.interaction.gbookList(list = page.list())
</section>
`)
