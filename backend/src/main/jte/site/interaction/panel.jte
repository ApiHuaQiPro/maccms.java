@import com.maccms.backend.site.web.view.HiddenField
@import com.maccms.backend.site.web.view.InteractionPanel
@param InteractionPanel panel
<section class="interaction-panel" aria-label="互动">
  @if(panel.hitsBeacon().isPresent())
  <img class="interaction-panel__beacon" src="${panel.hitsBeacon().get().href()}" alt="计数" width="1" height="1" hidden>
  @endif
  <div class="interaction-panel__digg">
    @template.site.interaction.inlineForm(form = panel.upForm(), extraClass = "interaction-form--up")
    @template.site.interaction.inlineForm(form = panel.downForm(), extraClass = "interaction-form--down")
  </div>
  <form class="interaction-form interaction-form--score" method="post" action="${panel.scoreAction().href()}">
    @for(HiddenField h : panel.scoreFields())
    <input type="hidden" name="${h.name()}" value="${h.value()}">
    @endfor
    <label class="interaction-form__label" for="interaction-score">评分</label>
    <select class="interaction-form__select" id="interaction-score" name="score">
      @for(Integer s : panel.scoreOptions())
      <option value="${s}">${s} 分</option>
      @endfor
    </select>
    <button class="interaction-form__button" type="submit">提交评分</button>
  </form>
  @if(panel.comments().isPresent())
  <p class="interaction-panel__comments"><a href="${panel.comments().get().href()}">查看与发表评论</a></p>
  @endif
</section>
