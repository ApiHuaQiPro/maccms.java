@import com.maccms.backend.site.web.view.ContentForm
@import com.maccms.backend.site.web.view.HiddenField
@param ContentForm form
@param String kind
<form class="content-action content-action--${kind}" method="post" action="${form.action().href()}">
  @for(HiddenField h : form.fields())
  <input type="hidden" name="${h.name()}" value="${h.value()}">
  @endfor
  <button class="content-action__submit" type="submit">${form.label()}</button>
</form>
