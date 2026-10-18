@import com.maccms.backend.site.web.view.UserLoginFragment
@param UserLoginFragment page
<div class="member-fragment member-fragment--login" data-site-fragment="user-login">
  @template.site.member.loginForm(form = page.form(), prefix = "member-ajax-login")
</div>
