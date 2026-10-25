@import com.maccms.backend.site.web.view.HiddenField
@import com.maccms.backend.site.web.view.MetadataItem
@import com.maccms.backend.site.web.view.UserPayPage
@param UserPayPage page
@template.site.layout.page(chrome = page.shell().chrome(), content = @`
<section class="member-page member-page--pay" data-site-page="user-pay" aria-labelledby="member-title">
  <h1 class="member-page__title" id="member-title">订单支付</h1>
  <dl class="member-summary">
    @for(MetadataItem m : page.details())
    <dt>${m.label()}</dt><dd>${m.value()}</dd>
    @endfor
  </dl>
  @if(page.paid())
  <p class="notice" role="status">该单据已支付完成</p>
  @else
  <h2 class="member-page__subtitle">选择支付方式</h2>
  <ul class="pay-methods">
    @for(UserPayPage.Method m : page.methods())
    <li class="pay-methods__item">
      <form class="pay-method" method="get" action="${page.gopay().href()}">
        @for(HiddenField h : page.fields())
        <input type="hidden" name="${h.name()}" value="${h.value()}">
        @endfor
        <input type="hidden" name="payment" value="${m.code()}">
        <button class="member-form__submit" type="submit">${m.name()}</button>
      </form>
    </li>
    @endfor
  </ul>
  @endif
  <p class="member-page__back"><a href="${page.back().url().href()}">${page.back().label()}</a></p>
</section>
@template.site.member.script(page = page.shell())
`)
