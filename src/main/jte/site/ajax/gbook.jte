@import com.maccms.backend.site.web.view.GbookPage
@param GbookPage page
<div class="gbook-fragment" data-site-fragment="gbook">
  <p class="gbook-page__total">共 ${page.list().total()} 条留言</p>
  @template.site.interaction.gbookList(list = page.list())
</div>
