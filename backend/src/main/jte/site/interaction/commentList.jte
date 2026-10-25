@import com.maccms.backend.site.web.view.BoardList
@import com.maccms.backend.site.web.view.CommentEntry
@param BoardList<CommentEntry> list
@if(list.entries().isEmpty())
<p class="interaction-empty">${list.empty()}</p>
@else
<ol class="comment-list">
  @for(CommentEntry c : list.entries())
  <li class="comment-list__item" id="comment-${c.id()}">
    <p class="comment-list__meta"><span class="comment-list__name">${c.name()}</span> <time class="comment-list__time">${c.time()}</time></p>
    <p class="comment-list__content">${c.text()}</p>
    <div class="comment-list__actions">
      @template.site.interaction.inlineForm(form = c.upForm(), extraClass = "interaction-form--up")
      <span class="comment-list__count">${c.up()}</span>
      @template.site.interaction.inlineForm(form = c.downForm(), extraClass = "interaction-form--down")
      <span class="comment-list__count">${c.down()}</span>
      @template.site.interaction.inlineForm(form = c.reportForm(), extraClass = "interaction-form--report")
    </div>
    @if(!c.replies().isEmpty())
    <ol class="comment-list comment-list--replies">
      @for(CommentEntry r : c.replies())
      <li class="comment-list__item comment-list__item--reply" id="comment-${r.id()}">
        <p class="comment-list__meta"><span class="comment-list__name">${r.name()}</span> <time class="comment-list__time">${r.time()}</time></p>
        <p class="comment-list__content">${r.text()}</p>
      </li>
      @endfor
    </ol>
    @endif
  </li>
  @endfor
</ol>
@endif
@if(list.pagination().isPresent())
@template.site.components.pagination(currentPage = list.pagination().get().currentPage(), pageCount = list.pagination().get().pageCount(), previousUrl = list.pagination().get().previousUrl(), nextUrl = list.pagination().get().nextUrl())
@endif
