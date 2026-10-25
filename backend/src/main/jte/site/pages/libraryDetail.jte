@import com.maccms.backend.site.web.view.LibraryDetailPage
@param LibraryDetailPage page
@template.site.layout.page(chrome = page.chrome(), content = @`
@template.site.fragments.libraryDetail(page = page)
@if(page.favorite().isPresent())
<section class="content-actions" aria-label="会员操作">
  @template.site.member.contentForm(form = page.favorite().get(), kind = "favorite")
</section>
@endif
@if(page.interaction().isPresent())
@template.site.interaction.panel(panel = page.interaction().get())
@endif`)
