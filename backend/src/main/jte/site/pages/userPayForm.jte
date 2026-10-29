@import com.maccms.backend.site.web.view.HiddenField
@import com.maccms.backend.site.web.view.UserPayFormPage
@param UserPayFormPage page
@template.site.layout.page(chrome = page.shell().chrome(), content = @`
<section class="member-page member-page--pay" data-site-page="user-pay-form" aria-labelledby="member-title">
  <h1 class="member-page__title" id="member-title">正在跳转到支付页面</h1>
  <form id="alipaysubmit" name="alipaysubmit" class="pay-method" method="post" action="${page.action().href()}" data-pay-autosubmit>
    @for(HiddenField h : page.fields())
    <input type="hidden" name="${h.name()}" value="${h.value()}">
    @endfor
    <button class="member-form__submit" type="submit">正在提交</button>
  </form>
  <p class="member-page__back"><a href="${page.back().url().href()}">${page.back().label()}</a></p>
</section>
@template.site.member.script(page = page.shell())
`)
