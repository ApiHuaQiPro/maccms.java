@import com.maccms.backend.site.web.view.MapPage
@import com.maccms.backend.site.web.view.NavLink
@param MapPage page
@template.site.layout.page(chrome = page.chrome(), content = @`
<section class="map-page" data-site-page="map">
  <h1 class="map-page__title">${page.heading()}</h1>
  @for(MapPage.Section section : page.sections())
  <section class="map-section" aria-labelledby="${section.id()}">
    <h2 class="map-section__title" id="${section.id()}">${section.title()}</h2>
    @template.site.fragments.mapTree(nodes = section.nodes())
  </section>
  @endfor
  @if(!page.links().isEmpty())
  <section class="map-section" aria-labelledby="map-links">
    <h2 class="map-section__title" id="map-links">站内入口</h2>
    <ul class="map-links">
      @for(NavLink link : page.links())
      <li class="map-links__item"><a href="${link.url().href()}">${link.label()}</a></li>
      @endfor
    </ul>
  </section>
  @endif
</section>
`)
