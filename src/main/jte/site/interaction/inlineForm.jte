@import com.maccms.backend.site.web.view.HiddenField
@import com.maccms.backend.site.web.view.InlineForm
@param InlineForm form
@param String extraClass
<form class="interaction-form interaction-form--inline ${extraClass}" method="post" action="${form.action().href()}">
  @for(HiddenField h : form.fields())
  <input type="hidden" name="${h.name()}" value="${h.value()}">
  @endfor
  <button class="interaction-form__button" type="submit">${form.label()}</button>
</form>
