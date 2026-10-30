@import com.maccms.backend.site.web.view.FrameNoticePage
@param FrameNoticePage page
@template.site.player.frame(frame = page.frame(), content = @`
<section class="access-notice notice" data-site-page="player-notice">
  <h1 class="access-notice__title notice__title">${page.heading()}</h1>
  <p class="access-notice__message">${page.message()}</p>
</section>
`)
