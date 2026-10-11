@import com.maccms.spike.jte.model.VideoCard
@param VideoCard card
<li class="video-card">
  <a href="${card.url()}" title="${card.name()}">
    <img src="${card.pic()}" alt="${card.name()}" loading="lazy" width="200" height="280">
    <span class="video-card__name">${card.name()}</span>
  </a>
  @if(!card.remarks().isEmpty())
  <span class="video-card__remarks">${card.remarks()}</span>
  @endif
  @if(card.pic().isEmpty())
  <span class="video-card__placeholder">暂无海报</span>
  @endif
</li>
