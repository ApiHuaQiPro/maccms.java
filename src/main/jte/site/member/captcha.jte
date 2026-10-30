@import com.maccms.backend.site.web.view.MemberCaptcha
@param MemberCaptcha captcha
@param String id
<div class="member-form__field member-form__field--captcha">
  <label class="member-form__label" for="${id}">验证码</label>
  <input class="member-form__input member-form__input--captcha" id="${id}" name="verify" type="text" maxlength="16" autocomplete="off" required>
  <button class="member-captcha" type="button" data-captcha-refresh aria-label="看不清，换一张">
    <img class="member-captcha__image" src="${captcha.src().href()}" data-captcha-src="${captcha.src().href()}" alt="验证码" width="128" height="40">
  </button>
</div>
