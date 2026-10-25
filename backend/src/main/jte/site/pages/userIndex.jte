@import com.maccms.backend.site.web.view.UserIndexPage
@param UserIndexPage page
@template.site.layout.page(chrome = page.shell().chrome(), content = @`
<section class="member-page member-page--index" data-site-page="user-index" aria-labelledby="member-title">
  <h1 class="member-page__title" id="member-title">会员中心</h1>
  <div class="member-profile">
    <img class="member-profile__avatar" src="${page.avatar().href()}" alt="头像" width="100" height="100" data-member-portrait>
    <dl class="member-profile__list">
      <dt>用户名</dt><dd>${page.profile().userName()}</dd>
      @if(!page.profile().nickName().isEmpty())
      <dt>昵称</dt><dd>${page.profile().nickName()}</dd>
      @endif
      <dt>会员组</dt><dd>${page.profile().groupName()}</dd>
      @if(page.profile().groupId() > 2 && !page.profile().endTimeText().isEmpty())
      <dt>到期时间</dt><dd>${page.profile().endTimeText()}</dd>
      @endif
      <dt>积分</dt><dd>${page.profile().points()}</dd>
      @if(!page.profile().regTimeText().isEmpty())
      <dt>注册时间</dt><dd>${page.profile().regTimeText()}</dd>
      @endif
      @if(!page.profile().loginTimeText().isEmpty())
      <dt>最近登录</dt><dd>${page.profile().loginTimeText()}</dd>
      @endif
    </dl>
  </div>
  <ul class="member-nav">
    <li><a href="${page.infoUrl().href()}" rel="nofollow">修改资料</a></li>
    <li><a href="${page.portraitUrl().href()}" rel="nofollow">修改头像</a></li>
    <li><a href="${page.logoutUrl().href()}" rel="nofollow">退出登录</a></li>
  </ul>
</section>
@template.site.member.script(page = page.shell())
`)
