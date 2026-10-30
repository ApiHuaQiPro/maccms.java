@import com.maccms.backend.site.web.view.CommentPage
@param CommentPage page
<div class="comment-fragment" data-site-fragment="comment">
  <p class="comment-page__total">共 ${page.list().total()} 条评论</p>
  @template.site.interaction.commentList(list = page.list())
  @if(page.form().isPresent())
  @template.site.interaction.postForm(form = page.form().get(), prefill = "")
  @endif
</div>
