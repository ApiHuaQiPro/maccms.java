@import com.maccms.backend.site.application.SafeUrl
@import java.util.Optional
@param int currentPage
@param int pageCount
@param Optional<SafeUrl> previousUrl
@param Optional<SafeUrl> nextUrl
<nav class="pagination" aria-label="分页">
  @if(previousUrl.isPresent())
  <a class="pagination__link pagination__link--prev" href="${previousUrl.get().href()}" rel="prev">上一页</a>
  @else
  <span class="pagination__link pagination__link--prev pagination__link--disabled" aria-disabled="true">上一页</span>
  @endif
  <span class="pagination__status" aria-current="page">第 ${currentPage} / ${pageCount} 页</span>
  @if(nextUrl.isPresent())
  <a class="pagination__link pagination__link--next" href="${nextUrl.get().href()}" rel="next">下一页</a>
  @else
  <span class="pagination__link pagination__link--next pagination__link--disabled" aria-disabled="true">下一页</span>
  @endif
</nav>
