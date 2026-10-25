@import com.maccms.backend.site.web.view.MetadataItem
@import com.maccms.backend.site.web.view.UserOrderPage
@param UserOrderPage page
@template.site.layout.page(chrome = page.shell().chrome(), content = @`
<section class="member-page member-page--order" data-site-page="user-order" aria-labelledby="member-title">
  <h1 class="member-page__title" id="member-title">订单详情</h1>
  @if(page.details().isEmpty())
  <p class="notice notice--error" role="alert">获取失败</p>
  @else
  <dl class="member-summary">
    @for(MetadataItem m : page.details())
    <dt>${m.label()}</dt><dd>${m.value()}</dd>
    @endfor
  </dl>
  @endif
  @if(page.pay().isPresent())
  <p class="member-page__intro"><a href="${page.pay().get().href()}">去支付</a></p>
  @endif
  <p class="member-page__back"><a href="${page.back().url().href()}">${page.back().label()}</a></p>
</section>
@template.site.member.script(page = page.shell())
`)
