package com.woocommerce.android.ui.products

import com.woocommerce.android.analytics.AnalyticsTracker
import com.woocommerce.android.analytics.AnalyticsTracker.Stat.PRODUCT_DETAIL_LOADED
import com.woocommerce.android.analytics.AnalyticsTracker.Stat.PRODUCT_DETAIL_UPDATE_ERROR
import com.woocommerce.android.analytics.AnalyticsTracker.Stat.PRODUCT_DETAIL_UPDATE_SUCCESS
import com.woocommerce.android.annotations.OpenClassOnDebug
import com.woocommerce.android.model.Product
import com.woocommerce.android.model.RequestResponse
import com.woocommerce.android.model.toAppModel
import com.woocommerce.android.model.toDataModel
import com.woocommerce.android.tools.SelectedSite
import com.woocommerce.android.util.WooLog
import com.woocommerce.android.util.WooLog.T.PRODUCTS
import com.woocommerce.android.util.suspendCoroutineWithTimeout
import com.woocommerce.android.util.suspendCancellableCoroutineWithTimeout
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode.MAIN
import org.wordpress.android.fluxc.Dispatcher
import org.wordpress.android.fluxc.action.WCProductAction.FETCH_SINGLE_PRODUCT
import org.wordpress.android.fluxc.action.WCProductAction.UPDATED_PRODUCT
import org.wordpress.android.fluxc.generated.WCProductActionBuilder
import org.wordpress.android.fluxc.store.WCProductStore
import org.wordpress.android.fluxc.store.WCProductStore.OnProductChanged
import org.wordpress.android.fluxc.store.WCProductStore.OnProductUpdated
import org.wordpress.android.fluxc.store.WCProductStore.ProductErrorType
import javax.inject.Inject
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

@OpenClassOnDebug
class ProductDetailRepository @Inject constructor(
    private val dispatcher: Dispatcher,
    private val productStore: WCProductStore,
    private val selectedSite: SelectedSite
) {
    companion object {
        private const val ACTION_TIMEOUT = 10L * 1000
    }

    private var continuationUpdateProduct: Continuation<RequestResponse>? = null
    private var continuation: CancellableContinuation<Boolean>? = null

    init {
        dispatcher.register(this)
    }

    fun onCleanup() {
        dispatcher.unregister(this)
    }

    suspend fun fetchProduct(remoteProductId: Long): Product? {
        try {
            continuation?.cancel()
            suspendCancellableCoroutineWithTimeout<Boolean>(ACTION_TIMEOUT) {
                continuation = it

                val payload = WCProductStore.FetchSingleProductPayload(selectedSite.get(), remoteProductId)
                dispatcher.dispatch(WCProductActionBuilder.newFetchSingleProductAction(payload))
            }
        } catch (e: CancellationException) {
            WooLog.d(PRODUCTS, "CancellationException while fetching single product")
        }

        continuation = null
        return getProduct(remoteProductId)
    }

    /**
     * Fires the request to update the product
     *
     * @return the result of the action as a [Boolean]
     */
    suspend fun updateProduct(updatedProduct: Product): RequestResponse {
        return try {
            suspendCoroutineWithTimeout<RequestResponse>(ACTION_TIMEOUT) {
                continuationUpdateProduct = it

                val payload = WCProductStore.UpdateProductPayload(
                        selectedSite.get(), updatedProduct.toDataModel(getCachedWCProductModel(updatedProduct.remoteId))
                )
                dispatcher.dispatch(WCProductActionBuilder.newUpdateProductAction(payload))
            } ?: RequestResponse.Error // request timed out
        } catch (e: CancellationException) {
            WooLog.e(PRODUCTS, "Exception encountered while updating product", e)
            RequestResponse.Error
        }
    }

    private fun getCachedWCProductModel(remoteProductId: Long) =
            productStore.getProductByRemoteId(selectedSite.get(), remoteProductId)

    fun getProduct(remoteProductId: Long): Product? = getCachedWCProductModel(remoteProductId)?.toAppModel()

    fun getCachedVariantCount(remoteProductId: Long) =
            productStore.getVariationsForProduct(selectedSite.get(), remoteProductId).size

    @SuppressWarnings("unused")
    @Subscribe(threadMode = MAIN)
    fun onProductChanged(event: OnProductChanged) {
        if (event.causeOfChange == FETCH_SINGLE_PRODUCT) {
            if (event.isError) {
                continuation?.resume(false)
            } else {
                AnalyticsTracker.track(PRODUCT_DETAIL_LOADED)
                continuation?.resume(true)
            }
        }
    }

    @SuppressWarnings("unused")
    @Subscribe(threadMode = MAIN)
    fun onProductUpdated(event: OnProductUpdated) {
        if (event.causeOfChange == UPDATED_PRODUCT) {
            if (event.isError) {
                AnalyticsTracker.track(PRODUCT_DETAIL_UPDATE_ERROR, mapOf(
                        AnalyticsTracker.KEY_ERROR_CONTEXT to this::class.java.simpleName,
                        AnalyticsTracker.KEY_ERROR_TYPE to event.error?.type?.toString(),
                        AnalyticsTracker.KEY_ERROR_DESC to event.error?.message))
                val errorResponse = if (event.error.type == ProductErrorType.DUPLICATE_SKU) {
                    RequestResponse.ProductIncorrectSku
                } else RequestResponse.Error
                continuationUpdateProduct?.resume(errorResponse)
            } else {
                AnalyticsTracker.track(PRODUCT_DETAIL_UPDATE_SUCCESS)
                continuationUpdateProduct?.resume(RequestResponse.Success)
            }
            continuationUpdateProduct = null
        }
    }
}
