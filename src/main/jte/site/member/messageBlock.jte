@import com.maccms.backend.site.web.view.UserMessageBlock
@param UserMessageBlock block
@param String prefix
<input type="hidden" name="ac" value="${block.ac()}">
<div class="member-form__field member-form__field--inline">
  <label class="member-form__label" for="${prefix}-to">${block.label()}</label>
  <input class="member-form__input" id="${prefix}-to" name="to" type="text" maxlength="64" autocomplete="off" required>
  <button class="member-form__send" type="button" data-member-send data-send-url="${block.sendAction().href()}">发送验证码</button>
</div>
<div class="member-form__field">
  <label class="member-form__label" for="${prefix}-code">${block.label()}验证码</label>
  <input class="member-form__input" id="${prefix}-code" name="code" type="text" maxlength="16" autocomplete="one-time-code" required>
</div>
