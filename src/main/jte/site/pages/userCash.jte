@import com.maccms.backend.site.web.view.MetadataItem
@import com.maccms.backend.site.web.view.UserCashPage
@param UserCashPage page
@template.site.layout.page(chrome = page.shell().chrome(), content = @`
<section class="member-page member-page--cash" data-site-page="user-cash" aria-labelledby="member-title">
  <h1 class="member-page__title" id="member-title">积分提现</h1>
  <dl class="member-summary">
    @for(MetadataItem m : page.summary())
    <dt>${m.label()}</dt><dd>${m.value()}</dd>
    @endfor
  </dl>
  @if(page.action().isPresent())
  <h2 class="member-page__subtitle" id="member-cash-title">申请提现</h2>
  <form class="member-form" method="post" action="${page.action().get().href()}" data-member-form="cash" data-success-url="${page.selfUrl().href()}" aria-labelledby="member-cash-title">
    <div class="member-form__field">
      <label class="member-form__label" for="member-cash-money">提现金额（元）</label>
      <input class="member-form__input" id="member-cash-money" name="cash_money" type="text" inputmode="decimal" maxlength="16" autocomplete="off" required>
    </div>
    <div class="member-form__field">
      <label class="member-form__label" for="member-cash-bank_name">银行名称</label>
      <input class="member-form__input" id="member-cash-bank_name" name="cash_bank_name" type="text" maxlength="60" required>
    </div>
    <div class="member-form__field">
      <label class="member-form__label" for="member-cash-bank_no">银行账号</label>
      <input class="member-form__input" id="member-cash-bank_no" name="cash_bank_no" type="text" maxlength="30" autocomplete="off" required>
    </div>
    <div class="member-form__field">
      <label class="member-form__label" for="member-cash-payee">收款人姓名</label>
      <input class="member-form__input" id="member-cash-payee" name="cash_payee_name" type="text" maxlength="30" required>
    </div>
    <p class="member-form__message" role="status" aria-live="polite" data-member-message hidden></p>
    <button class="member-form__submit" type="submit">提交申请</button>
  </form>
  @else
  <p class="notice">提现功能未开启！</p>
  @endif
  <h2 class="member-page__subtitle">提现记录</h2>
  @template.site.member.table(table = page.table())
  <p class="member-page__back"><a href="${page.back().url().href()}">${page.back().label()}</a></p>
</section>
@template.site.member.script(page = page.shell())
`)
