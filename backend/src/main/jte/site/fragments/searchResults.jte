@import com.maccms.backend.site.web.view.SearchPage
@import com.maccms.backend.site.web.view.VideoCardView
@param SearchPage page
@if(page.total().isPresent())
<section class="search-page__results" aria-labelledby="search-results-title">
  <h2 class="search-page__results-title" id="search-results-title">搜索结果</h2>
@if(page.videos().isEmpty())
  <p class="search-empty notice">没有找到相关视频</p>
@else
  <ul class="video-grid">
  @for(VideoCardView v : page.videos())
    <li class="video-grid__item">
    @template.site.components.videoCard(title = v.title(), remarks = v.remarks(), detailUrl = v.detailUrl(), imageUrl = v.imageUrl())
    </li>
  @endfor
  </ul>
@endif
</section>
@endif
@if(page.random() && page.total().isPresent())
<p class="search-random-notice notice">随机排序每次显示不同的结果，不提供翻页。</p>
@endif
@if(page.pagination().isPresent())
@template.site.components.pagination(currentPage = page.pagination().get().currentPage(), pageCount = page.pagination().get().pageCount(), previousUrl = page.pagination().get().previousUrl(), nextUrl = page.pagination().get().nextUrl())
@endif
