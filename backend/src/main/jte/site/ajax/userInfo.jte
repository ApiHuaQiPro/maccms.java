@import com.maccms.backend.site.web.view.UserInfoFragment
@param UserInfoFragment page
<div class="member-fragment member-fragment--info" data-site-fragment="user-info">
  <img class="member-profile__avatar" src="${page.avatar().href()}" alt="头像" width="48" height="48">
  <dl class="member-profile__list">
    <dt>用户名</dt><dd>${page.profile().userName()}</dd>
    <dt>会员组</dt><dd>${page.profile().groupName()}</dd>
    <dt>积分</dt><dd>${page.profile().points()}</dd>
  </dl>
  <ul class="member-nav">
    <li><a href="${page.indexUrl().href()}" rel="nofollow">会员中心</a></li>
    <li><a href="${page.logoutUrl().href()}" rel="nofollow">退出登录</a></li>
  </ul>
</div>
