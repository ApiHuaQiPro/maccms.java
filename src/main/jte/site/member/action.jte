@import com.maccms.backend.site.web.view.HiddenField
@import com.maccms.backend.site.web.view.MemberAction
@param MemberAction action
<form class="member-form member-form--inline" method="post" action="${action.action().href()}" data-member-form="${action.kind()}" data-success-url="${action.successUrl().href()}">
  @for(HiddenField h : action.fields())
  <input type="hidden" name="${h.name()}" value="${h.value()}">
  @endfor
  <button class="member-form__submit member-form__submit--small" type="submit">${action.label()}</button>
  <p class="member-form__message" role="status" aria-live="polite" data-member-message hidden></p>
</form>
