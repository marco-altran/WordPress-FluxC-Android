package org.wordpress.android.fluxc.example

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import dagger.android.AndroidInjection
import kotlinx.android.synthetic.main.activity_wc_order_list.*
import org.apache.commons.lang3.time.DateUtils
import org.wordpress.android.fluxc.Dispatcher
import org.wordpress.android.fluxc.example.TimeGroup.GROUP_OLDER_MONTH
import org.wordpress.android.fluxc.example.TimeGroup.GROUP_OLDER_TWO_DAYS
import org.wordpress.android.fluxc.example.TimeGroup.GROUP_OLDER_WEEK
import org.wordpress.android.fluxc.example.TimeGroup.GROUP_TODAY
import org.wordpress.android.fluxc.example.TimeGroup.GROUP_YESTERDAY
import org.wordpress.android.fluxc.example.WCOrderListItemIdentifier.OrderIdentifier
import org.wordpress.android.fluxc.example.WCOrderListItemIdentifier.SectionHeaderIdentifier
import org.wordpress.android.fluxc.example.WCOrderListItemUIType.LoadingItem
import org.wordpress.android.fluxc.example.WCOrderListItemUIType.SectionHeader
import org.wordpress.android.fluxc.example.WCOrderListItemUIType.WCOrderListUIItem
import org.wordpress.android.fluxc.generated.WCOrderActionBuilder
import org.wordpress.android.fluxc.model.LocalOrRemoteId.RemoteId
import org.wordpress.android.fluxc.model.WCOrderListDescriptor
import org.wordpress.android.fluxc.model.WCOrderSummaryModel
import org.wordpress.android.fluxc.model.list.PagedListWrapper
import org.wordpress.android.fluxc.model.list.datasource.ListItemDataSourceInterface
import org.wordpress.android.fluxc.store.ListStore
import org.wordpress.android.fluxc.store.WCOrderStore
import org.wordpress.android.fluxc.store.WCOrderStore.FetchOrderListPayload
import org.wordpress.android.fluxc.store.WooCommerceStore
import org.wordpress.android.util.DateTimeUtils
import java.util.Date
import javax.inject.Inject

class WCOrderListActivity : AppCompatActivity() {
    @Inject internal lateinit var dispatcher: Dispatcher
    @Inject internal lateinit var wooCommerceStore: WooCommerceStore
    @Inject internal lateinit var wcOrderStore: WCOrderStore
    @Inject internal lateinit var listStore: ListStore

    private var swipeRefreshLayout: SwipeRefreshLayout? = null
    private var progressLoadMore: ProgressBar? = null
    private var pagedListWrapper: PagedListWrapper<WCOrderListItemUIType>? = null
    private val orderListAdapter: OrderListAdapter = OrderListAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wc_order_list)

        swipeRefreshLayout = findViewById(R.id.ptr_layout)
        progressLoadMore = findViewById(R.id.progress)

        findViewById<RecyclerView>(R.id.recycler_view)?.apply {
            adapter = orderListAdapter
            layoutManager = LinearLayoutManager(context)
            itemAnimator = DefaultItemAnimator()
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
        }
    }

    override fun onStart() {
        super.onStart()

        val orderListDescriptor = WCOrderListDescriptor(
                site = wooCommerceStore.getWooCommerceSites()[0], // crash if site is not there
                statusFilter = null,
                searchQuery = ""
        )

        loadList(orderListDescriptor)

        swipeRefreshLayout?.apply {
            setColorSchemeColors(
                    ContextCompat.getColor(this@WCOrderListActivity, android.R.color.holo_blue_bright),
                    ContextCompat.getColor(this@WCOrderListActivity, android.R.color.holo_green_light),
                    ContextCompat.getColor(this@WCOrderListActivity, android.R.color.holo_orange_dark))
            setOnRefreshListener {
                pagedListWrapper?.fetchFirstPage()
            }
        }

        order_search_submit.setOnClickListener {
            val descriptor = WCOrderListDescriptor(
                    site = wooCommerceStore.getWooCommerceSites()[0], // crash if site is not there
                    statusFilter = order_filter.text.toString(),
                    searchQuery = order_search_query.text.toString()
            )
            loadList(descriptor)
        }

        order_search_clear.setOnClickListener {
            order_search_query.text.clear()
            order_filter.text.clear()
            val descriptor = WCOrderListDescriptor(
                    site = wooCommerceStore.getWooCommerceSites()[0] // crash if site is not there
            )
            loadList(descriptor)
        }
    }

    private fun loadList(descriptor: WCOrderListDescriptor) {
        pagedListWrapper?.apply {
            val lifecycleOwner = this@WCOrderListActivity
            data.removeObservers(lifecycleOwner)
            isLoadingMore.removeObservers(lifecycleOwner)
            isFetchingFirstPage.removeObservers(lifecycleOwner)
            listError.removeObservers(lifecycleOwner)
            isEmpty.removeObservers(lifecycleOwner)
        }

        pagedListWrapper = listStore.getList(
                listDescriptor = descriptor,
                dataSource = WCOrderListItemDataSource(dispatcher, wcOrderStore, lifecycle),
                lifecycle = lifecycle
        ).also { wrapper ->
            wrapper.fetchFirstPage()
            wrapper.isLoadingMore.observe(this, Observer {
                it?.let { isLoadingMore ->
                    progressLoadMore?.visibility = if (isLoadingMore) View.VISIBLE else View.GONE
                }
            })
            wrapper.isFetchingFirstPage.observe(this, Observer {
                swipeRefreshLayout?.isRefreshing = it == true
            })
            wrapper.data.observe(this, Observer {
                it?.let { orderListData ->
                    orderListAdapter.submitList(orderListData)
                }
            })
        }
    }
}

sealed class WCOrderListItemIdentifier {
    class SectionHeaderIdentifier(val title: TimeGroup) : WCOrderListItemIdentifier()
    class OrderIdentifier(val remoteId: RemoteId) : WCOrderListItemIdentifier()
}

sealed class WCOrderListItemUIType {
    class SectionHeader(val title: TimeGroup) : WCOrderListItemUIType()
    class LoadingItem(val remoteId: RemoteId) : WCOrderListItemUIType()
    data class WCOrderListUIItem(
        val remoteOrderId: RemoteId,
        val orderNumber: String,
        val status: String,
        val orderName: String,
        val orderTotal: String
    ) : WCOrderListItemUIType()
}

enum class TimeGroup {
    GROUP_TODAY,
    GROUP_YESTERDAY,
    GROUP_OLDER_TWO_DAYS,
    GROUP_OLDER_WEEK,
    GROUP_OLDER_MONTH;

    companion object {
        fun getTimeGroupForDate(date: Date): TimeGroup {
            val dateToday = Date()
            return when {
                date < DateUtils.addMonths(dateToday, -1) -> GROUP_OLDER_MONTH
                date < DateUtils.addWeeks(dateToday, -1) -> GROUP_OLDER_WEEK
                date < DateUtils.addDays(dateToday, -2) -> GROUP_OLDER_TWO_DAYS
                DateUtils.isSameDay(DateUtils.addDays(dateToday, -2), date) -> GROUP_OLDER_TWO_DAYS
                DateUtils.isSameDay(DateUtils.addDays(dateToday, -1), date) -> GROUP_YESTERDAY
                else -> GROUP_TODAY
            }
        }
    }
}

private class WCOrderListItemDataSource(
    val dispatcher: Dispatcher,
    val wcOrderStore: WCOrderStore,
    val lifecycle: Lifecycle
) : ListItemDataSourceInterface<WCOrderListDescriptor, WCOrderListItemIdentifier, WCOrderListItemUIType> {
    private val fetcher = WCOrderFetcher(lifecycle, dispatcher)

    override fun getItemsAndFetchIfNecessary(
        listDescriptor: WCOrderListDescriptor,
        itemIdentifiers: List<WCOrderListItemIdentifier>
    ): List<WCOrderListItemUIType> {
        val remoteItemIds = itemIdentifiers.mapNotNull { (it as? OrderIdentifier)?.remoteId }
        val ordersMap = wcOrderStore.getOrdersForDescriptor(listDescriptor, remoteItemIds)
        // Fetch missing items
        fetcher.fetchOrders(
                site = listDescriptor.site,
                remoteItemIds = remoteItemIds.filter { !ordersMap.containsKey(it) }
        )

        val mapSummary = { remoteOrderId: RemoteId ->
            ordersMap[remoteOrderId].let { order ->
                if (order == null) {
                    LoadingItem(remoteOrderId)
                } else {
                    WCOrderListUIItem(
                            remoteOrderId = RemoteId(order.remoteOrderId),
                            orderNumber = order.number,
                            status = order.status,
                            orderName = "${order.billingFirstName} ${order.billingLastName}",
                            orderTotal = order.total
                    )
                }
            }
        }
        return itemIdentifiers.map { identifier ->
            when (identifier) {
                is OrderIdentifier -> mapSummary(identifier.remoteId)
                is SectionHeaderIdentifier -> SectionHeader(title = identifier.title)
            }
        }
    }

    override fun getItemIdentifiers(
        listDescriptor: WCOrderListDescriptor,
        remoteItemIds: List<RemoteId>,
        isListFullyFetched: Boolean
    ): List<WCOrderListItemIdentifier> {
        val orderSummaries = wcOrderStore.getOrderSummariesByRemoteOrderIds(listDescriptor.site, remoteItemIds)
                .let { summariesByRemoteId ->
                    remoteItemIds.mapNotNull { summariesByRemoteId[it] }
                }

        val listToday = ArrayList<OrderIdentifier>()
        val listYesterday = ArrayList<OrderIdentifier>()
        val listTwoDays = ArrayList<OrderIdentifier>()
        val listWeek = ArrayList<OrderIdentifier>()
        val listMonth = ArrayList<OrderIdentifier>()
        val mapToRemoteOrderIdentifier = { summary: WCOrderSummaryModel ->
            OrderIdentifier(RemoteId(summary.remoteOrderId))
        }
        orderSummaries.forEach {
            // Default to today if the date cannot be parsed
            val date: Date = DateTimeUtils.dateUTCFromIso8601(it.dateCreated) ?: Date()
            when (TimeGroup.getTimeGroupForDate(date)) {
                GROUP_TODAY -> listToday.add(mapToRemoteOrderIdentifier(it))
                GROUP_YESTERDAY -> listYesterday.add(mapToRemoteOrderIdentifier(it))
                GROUP_OLDER_TWO_DAYS -> listTwoDays.add(mapToRemoteOrderIdentifier(it))
                GROUP_OLDER_WEEK -> listWeek.add(mapToRemoteOrderIdentifier(it))
                GROUP_OLDER_MONTH -> listMonth.add(mapToRemoteOrderIdentifier(it))
            }
        }

        val allItems = mutableListOf<WCOrderListItemIdentifier>()
        if (listToday.isNotEmpty()) {
            allItems += listOf(SectionHeaderIdentifier(GROUP_TODAY)) + listToday
        }
        if (listYesterday.isNotEmpty()) {
            allItems += listOf(SectionHeaderIdentifier(GROUP_YESTERDAY)) + listYesterday
        }
        if (listTwoDays.isNotEmpty()) {
            allItems += listOf(SectionHeaderIdentifier(GROUP_OLDER_TWO_DAYS)) + listTwoDays
        }
        if (listWeek.isNotEmpty()) {
            allItems += listOf(SectionHeaderIdentifier(GROUP_OLDER_WEEK)) + listWeek
        }
        if (listMonth.isNotEmpty()) {
            allItems += listOf(SectionHeaderIdentifier(GROUP_OLDER_MONTH)) + listMonth
        }
        return allItems
    }

    override fun fetchList(listDescriptor: WCOrderListDescriptor, offset: Long) {
        val fetchOrderListPayload = FetchOrderListPayload(listDescriptor, offset)
        dispatcher.dispatch(WCOrderActionBuilder.newFetchOrderListAction(fetchOrderListPayload))
    }
}