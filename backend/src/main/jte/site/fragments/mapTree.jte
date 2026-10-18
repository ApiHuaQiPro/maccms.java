@import com.maccms.backend.site.web.view.MapPage
@import java.util.List
@param List<MapPage.Node> nodes
<ul class="map-tree">
  @for(MapPage.Node node : nodes)
  <li class="map-tree__item">
    <a href="${node.link().url().href()}">${node.link().label()}</a>
    @if(!node.children().isEmpty())
    @template.site.fragments.mapTree(nodes = node.children())
    @endif
  </li>
  @endfor
</ul>
