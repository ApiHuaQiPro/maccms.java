@import com.maccms.backend.site.web.view.SearchPage
@param SearchPage page
<div class="search-page__fragment" data-site-page="${page.siteKind()}-fragment">
@template.site.fragments.searchResults(page = page)
</div>
