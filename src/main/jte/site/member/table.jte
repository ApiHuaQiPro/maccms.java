@import com.maccms.backend.site.web.view.UserTable
@param UserTable table
@if(table.rows().isEmpty())
<p class="member-empty">${table.empty()}</p>
@else
<div class="member-table-wrap">
  <table class="member-table">
    <caption class="member-table__caption">${table.caption()}</caption>
    <thead>
      <tr>
        @for(String h : table.headers())
        <th scope="col">${h}</th>
        @endfor
        @if(table.hasActions())
        <th scope="col">操作</th>
        @endif
      </tr>
    </thead>
    <tbody>
      @for(UserTable.Row row : table.rows())
      <tr>
        @for(UserTable.Cell cell : row.cells())
        @if(cell.link().isPresent())
        <td><a href="${cell.link().get().href()}">${cell.text()}</a></td>
        @else
        <td>${cell.text()}</td>
        @endif
        @endfor
        @if(table.hasActions())
        <td class="member-table__actions">
          @if(row.action().isPresent())
          @template.site.member.action(action = row.action().get())
          @endif
        </td>
        @endif
      </tr>
      @endfor
    </tbody>
  </table>
</div>
@endif
@if(table.pagination().isPresent())
@template.site.components.pagination(currentPage = table.pagination().get().currentPage(), pageCount = table.pagination().get().pageCount(), previousUrl = table.pagination().get().previousUrl(), nextUrl = table.pagination().get().nextUrl())
@endif
