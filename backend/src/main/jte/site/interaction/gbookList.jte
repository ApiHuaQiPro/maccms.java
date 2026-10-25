@import com.maccms.backend.site.web.view.BoardList
@import com.maccms.backend.site.web.view.GbookEntry
@param BoardList<GbookEntry> list
@if(list.entries().isEmpty())
<p class="interaction-empty">${list.empty()}</p>
@else
<ol class="gbook-list">
  @for(GbookEntry g : list.entries())
  <li class="gbook-list__item" id="gbook-${g.id()}">
    <p class="gbook-list__meta"><span class="gbook-list__name">${g.name()}</span> <time class="gbook-list__time">${g.time()}</time></p>
    <p class="gbook-list__content">${g.text()}</p>
    @if(!g.reply().isEmpty())
    <p class="gbook-list__reply"><span class="gbook-list__reply-label">管理员回复</span> <time class="gbook-list__time">${g.replyTime()}</time> ${g.reply()}</p>
    @endif
  </li>
  @endfor
</ol>
@endif
@if(list.pagination().isPresent())
@template.site.components.pagination(currentPage = list.pagination().get().currentPage(), pageCount = list.pagination().get().pageCount(), previousUrl = list.pagination().get().previousUrl(), nextUrl = list.pagination().get().nextUrl())
@endif
