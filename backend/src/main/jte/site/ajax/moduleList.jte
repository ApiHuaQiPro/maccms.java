@import com.maccms.backend.site.web.view.ModuleListView
@param ModuleListView page
<div class="${page.pageClass()}" data-site-page="${page.siteKind()}-fragment">
@template.site.fragments.moduleResults(page = page)
</div>
