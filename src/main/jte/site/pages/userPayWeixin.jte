@import com.maccms.backend.site.web.view.MetadataItem
@import com.maccms.backend.site.web.view.UserPayWeixinPage
@param UserPayWeixinPage page
@template.site.layout.page(chrome = page.shell().chrome(), content = @`
<section class="member-page member-page--pay" data-site-page="user-pay-weixin" aria-labelledby="member-title">
  <h1 class="member-page__title" id="member-title">微信支付</h1>
  <dl class="member-summary">
    @for(MetadataItem m : page.details())
    <dt>${m.label()}</dt><dd>${m.value()}</dd>
    @endfor
  </dl>
  @if(page.qrcode().isPresent())
  <p class="member-page__intro">请使用微信扫描二维码完成支付。</p>
  <p class="member-page__intro"><img src="${page.qrcode().get().href()}" alt="微信支付二维码" width="290" height="290"></p>
  <p class="member-page__intro"><a href="${page.orders().href()}">支付完成后查看订单</a></p>
  @else
  <p class="notice notice--error" role="alert">获取微信二维码失败</p>
  @endif
  <p class="member-page__back"><a href="${page.back().url().href()}">${page.back().label()}</a></p>
</section>
@template.site.member.script(page = page.shell())
`)
