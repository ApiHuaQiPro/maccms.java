@import com.maccms.backend.site.web.view.DetailPage
@param DetailPage page
@template.site.layout.page(chrome = page.chrome(), content = @`
@template.site.fragments.videoDetail(page = page)
@if(page.favorite().isPresent())
<section class="content-actions" aria-label="会员操作">
  @template.site.member.contentForm(form = page.favorite().get(), kind = "favorite")
</section>
@endif`)
