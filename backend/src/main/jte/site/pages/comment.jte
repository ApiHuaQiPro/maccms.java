@import com.maccms.backend.site.web.view.CommentPage
@param CommentPage page
@template.site.layout.page(chrome = page.chrome(), content = @`
<section class="comment-page" data-site-page="comment" aria-labelledby="comment-title">
  <h1 class="comment-page__title" id="comment-title">${page.heading()}</h1>
  @if(page.target().isPresent())
  <p class="comment-page__target"><a href="${page.target().get().href()}">返回内容页</a></p>
  @endif
  <p class="comment-page__total">共 ${page.list().total()} 条评论</p>
  @template.site.interaction.commentList(list = page.list())
  @if(page.form().isPresent())
  @template.site.interaction.postForm(form = page.form().get(), prefill = "")
  @endif
</section>
`)
