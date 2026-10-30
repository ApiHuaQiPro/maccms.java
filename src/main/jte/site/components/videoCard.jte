@import com.maccms.backend.site.application.SafeUrl
@import java.util.Optional
@param String title
@param String remarks
@param SafeUrl detailUrl
@param Optional<SafeUrl> imageUrl
<article class="video-card">
  <a class="video-card__link" href="${detailUrl.href()}">
    <div class="video-card__poster">
      @if(imageUrl.isPresent())
      <img class="video-card__image" src="${imageUrl.get().href()}" alt="${title}" loading="lazy">
      @else
      <span class="video-card__placeholder" aria-hidden="true"></span>
      @endif
    </div>
    <h3 class="video-card__title">${title}</h3>
    @if(!remarks.isEmpty())
    <p class="video-card__remarks">${remarks}</p>
    @endif
  </a>
</article>
