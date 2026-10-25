@import com.maccms.backend.site.application.SafeUrl
@import com.maccms.backend.site.web.view.PlayerFramePage
@param PlayerFramePage page
@template.site.player.frame(frame = page.frame(), content = @`
<div id="player-stage" class="player-stage" data-trial-minutes="${page.trialMinutes()}" data-trial-end-message="${page.trialEndMessage().isEmpty() ? null : page.trialEndMessage()}" data-player="${page.playerData()}">
  @for(SafeUrl s : page.scripts())
  <script nonce="${page.nonce()}" src="${s.href()}"></script>
  @endfor
</div>
@if(page.trialScript().isPresent())
<p id="trial-message" class="trial-message" role="status" hidden></p>
<script nonce="${page.nonce()}" src="${page.trialScript().get().href()}"></script>
@endif
`)
