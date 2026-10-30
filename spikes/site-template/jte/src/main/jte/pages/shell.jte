@import com.maccms.spike.jte.model.ShellPage
@import com.maccms.spike.jte.model.VideoCard
@param ShellPage page
@template.layout.page(meta = page.meta(), site = page.site(), nav = page.nav(), content = @`
<main id="main">
  <h1>${page.heading()}</h1>
  @if(page.cards().isEmpty())
  <p class="empty-state">暂无内容</p>
  @else
  <ul class="video-grid">
    @for(VideoCard card : page.cards())
    @template.fragments.card(card = card)
    @endfor
  </ul>
  @endif
</main>
`)
