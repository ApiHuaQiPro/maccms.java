<%-- MACC-7 失配实验：remarks 故意写成 remakrs，位于测试数据会走到的分支 --%>
@import com.maccms.spike.jte.model.VideoCard
@param VideoCard card
<li class="video-card">
  <a href="${card.url()}" title="${card.name()}">
    <img src="${card.pic()}" alt="${card.name()}" loading="lazy" width="200" height="280">
    <span class="video-card__name">${card.name()}</span>
  </a>
  @if(!card.remarks().isEmpty())
  <span class="video-card__remarks">${card.remakrs()}</span>
  @endif
  @if(card.pic().isEmpty())
  <span class="video-card__placeholder">暂无海报</span>
  @endif
</li>
