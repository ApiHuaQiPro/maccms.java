@import com.maccms.backend.site.web.view.MetadataItem
@import com.maccms.backend.site.web.view.NavLink
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
  @if(!page.finance().isEmpty())
  <h2 class="member-page__subtitle" id="member-finance-title">会员权益</h2>
  <ul class="member-nav member-nav--finance" aria-labelledby="member-finance-title">
    @for(NavLink n : page.finance())
    <li><a href="${n.url().href()}" rel="nofollow">${n.label()}</a></li>
    @endfor
  </ul>
  @endif
  @if(!page.promotion().isEmpty())
  <h2 class="member-page__subtitle" id="member-promotion-title">推广链接</h2>
  <dl class="member-summary member-summary--promotion" aria-labelledby="member-promotion-title">
    @for(MetadataItem m : page.promotion())
    <dt>${m.label()}</dt><dd class="member-summary__url">${m.value()}</dd>
    @endfor
  </dl>
  @endif
</section>
@template.site.member.script(page = page.shell())
`)
