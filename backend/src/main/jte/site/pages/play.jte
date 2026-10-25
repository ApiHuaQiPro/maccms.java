@import com.maccms.backend.site.web.view.EpisodeLink
@import com.maccms.backend.site.web.view.NavLink
@import com.maccms.backend.site.web.view.PlayPage
@import com.maccms.backend.site.web.view.PlaySourceView
@param PlayPage page
@template.site.layout.page(chrome = page.chrome(), content = @`
<article class="player-page" data-site-page="play">
  <nav class="breadcrumb" aria-label="当前位置">
    <ol class="breadcrumb__list">
      @for(NavLink b : page.breadcrumb())
      <li class="breadcrumb__item">
        @if(b.current())
        <span class="breadcrumb__current" aria-current="page">${b.label()}</span>
        @else
        <a class="breadcrumb__link" href="${b.url().href()}">${b.label()}</a>
        @endif
      </li>
      @endfor
    </ol>
  </nav>
  <h1 class="player-page__title">${page.title()}</h1>
  <p class="player-page__current">${page.current()}</p>
  @if(page.trial())
  <p class="player-page__trial notice">当前为试看模式，试看时间到后播放会停止。</p>
  @endif
  @if(page.trialOffer().isPresent())
  <div class="trial-offer notice">
    <p class="trial-offer__message">${page.trialOffer().get().message()}</p>
    @if(page.trialOffer().get().form().isPresent())
    @template.site.member.contentForm(form = page.trialOffer().get().form().get(), kind = "buy")
    @endif
    @if(page.trialOffer().get().recharge().isPresent())
    <p class="trial-offer__recharge"><a href="${page.trialOffer().get().recharge().get().href()}" rel="nofollow">去充值</a></p>
    @endif
  </div>
  @endif
  <div class="player-frame">
    <iframe class="player-frame__iframe" src="${page.playerUrl().href()}" title="${page.playerTitle()}" allowfullscreen></iframe>
  </div>
  <nav class="player-page__nav" aria-label="选集">
    @if(page.previous().isPresent())
    <a class="player-page__prev" href="${page.previous().get().href()}" rel="prev">上一集</a>
    @endif
    <a class="player-page__detail" href="${page.detailUrl().href()}">返回详情</a>
    @if(page.next().isPresent())
    <a class="player-page__next" href="${page.next().get().href()}" rel="next">下一集</a>
    @endif
  </nav>
  <section class="player-sources" aria-labelledby="player-sources-title">
    <h2 class="player-sources__title" id="player-sources-title">播放列表</h2>
    @for(PlaySourceView s : page.sources())
    <div class="player-sources__group">
      <h3 class="player-sources__name" id="${s.key()}-title">${s.label()}</h3>
      @if(!s.note().isEmpty())
      <p class="player-sources__note">${s.note()}</p>
      @endif
      <ul class="player-episodes" aria-labelledby="${s.key()}-title">
        @for(EpisodeLink e : s.episodes())
        <li class="player-episodes__item">
          @if(e.current())
          <a class="player-episodes__link player-episodes__link--current" href="${e.url().href()}" aria-current="page">${e.label()}</a>
          @else
          <a class="player-episodes__link" href="${e.url().href()}">${e.label()}</a>
          @endif
        </li>
        @endfor
      </ul>
    </div>
    @endfor
  </section>
</article>
`)
