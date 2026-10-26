@import com.maccms.backend.content.application.view.AdminTypeNode
@import com.maccms.backend.admin.web.view.content.ViewEq
@import java.util.List
@param List<AdminTypeNode> types
@param long mid
@param String selected
@param boolean form
@for(AdminTypeNode vo : types)@if(vo.row().n("type_mid") == mid)
<option value="${vo.row().s("type_id")}" selected="${selected != null && ViewEq.eq(selected, vo.row().s("type_id"))}">${vo.row().s("type_name")}</option>
@for(AdminTypeNode ch : vo.children())
<option value="${ch.row().s("type_id")}" selected="${selected != null && ViewEq.eq(selected, ch.row().s("type_id"))}">@if(form)&nbsp;|&nbsp;&nbsp;&nbsp;|—@else&nbsp;&nbsp;&nbsp;&nbsp;├&nbsp;@endif${ch.row().s("type_name")}</option>
@endfor
@endif@endfor
