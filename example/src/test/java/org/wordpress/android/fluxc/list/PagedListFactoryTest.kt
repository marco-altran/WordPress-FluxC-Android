package org.wordpress.android.fluxc.list

import android.arch.paging.DataSource.InvalidatedCallback
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.times
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import org.junit.Test
import org.wordpress.android.fluxc.model.list.PagedListFactory

internal class PagedListFactoryTest {
    @Test
    fun `create factory triggers create data store`() {
        val mockCreateDataSource = mock<() -> TestInternalPagedListDataSource>()
        whenever(mockCreateDataSource.invoke()).thenReturn(mock())
        val pagedListFactory = PagedListFactory(mockCreateDataSource)

        pagedListFactory.create()

        verify(mockCreateDataSource, times(1)).invoke()
    }

    @Test
    fun `invalidate triggers create data store`() {
        val mockCreateDataSource = mock<() -> TestInternalPagedListDataSource>()
        whenever(mockCreateDataSource.invoke()).thenReturn(mock())
        val invalidatedCallback = mock<InvalidatedCallback>()

        val pagedListFactory = PagedListFactory(mockCreateDataSource)
        val currentSource = pagedListFactory.create()
        currentSource.addInvalidatedCallback(invalidatedCallback)

        pagedListFactory.invalidate()

        verify(invalidatedCallback, times(1)).onInvalidated()
    }
}
