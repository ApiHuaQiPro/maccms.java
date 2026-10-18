@import com.maccms.backend.site.web.view.LibraryListPage
@param LibraryListPage page
@template.site.layout.page(chrome = page.chrome(), content = @`
@template.site.fragments.libraryList(page = page)
`)
