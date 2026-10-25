@import com.maccms.backend.site.web.view.UserBuyPage
@param UserBuyPage page
@template.site.layout.page(chrome = page.shell().chrome(), content = @`
<section class="member-page member-page--buy" data-site-page="user-buy" aria-labelledby="member-title">
  <h1 class="member-page__title" id="member-title">充值</h1>
  <dl class="member-summary">
    <dt>当前积分</dt><dd>${page.points()}</dd>
  </dl>
  <h2 class="member-page__subtitle" id="member-buy-card-title">充值卡充值</h2>
  <form class="member-form" method="post" action="${page.action().href()}" data-member-form="buy_card" data-success-url="${page.selfUrl().href()}" aria-labelledby="member-buy-card-title">
    <input type="hidden" name="flag" value="card">
    <div class="member-form__field">
      <label class="member-form__label" for="member-buy-card_no">卡号</label>
      <input class="member-form__input" id="member-buy-card_no" name="card_no" type="text" maxlength="16" autocomplete="off" required>
    </div>
    <div class="member-form__field">
      <label class="member-form__label" for="member-buy-card_pwd">卡密</label>
      <input class="member-form__input" id="member-buy-card_pwd" name="card_pwd" type="text" maxlength="8" autocomplete="off" required>
    </div>
    <p class="member-form__message" role="status" aria-live="polite" data-member-message hidden></p>
    <button class="member-form__submit" type="submit">充值</button>
  </form>
  @if(page.cardShop().isPresent())
  <p class="member-page__intro"><a href="${page.cardShop().get().href()}">购买充值卡</a></p>
  @endif
  <h2 class="member-page__subtitle" id="member-buy-order-title">在线充值</h2>
  <form class="member-form" method="post" action="${page.action().href()}" data-member-form="buy_order" data-success-url="${page.ordersUrl().href()}" aria-labelledby="member-buy-order-title">
    <div class="member-form__field">
      <label class="member-form__label" for="member-buy-price">充值金额（元）</label>
      <input class="member-form__input" id="member-buy-price" name="price" type="text" inputmode="decimal" maxlength="16" autocomplete="off" required aria-describedby="member-buy-price-hint">
      <p class="member-form__hint" id="member-buy-price-hint">最小充值金额 ${page.minText()} 元，每元可得 ${page.scaleText()} 积分。提交后生成订单，在"我的订单"中去支付。</p>
    </div>
    <p class="member-form__message" role="status" aria-live="polite" data-member-message hidden></p>
    <button class="member-form__submit" type="submit">生成订单</button>
  </form>
  <p class="member-page__back"><a href="${page.back().url().href()}">${page.back().label()}</a></p>
</section>
@template.site.member.script(page = page.shell())
`)
