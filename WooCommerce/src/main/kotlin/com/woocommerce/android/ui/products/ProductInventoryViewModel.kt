package com.woocommerce.android.ui.products

import android.content.DialogInterface
import android.os.Parcelable
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import com.woocommerce.android.R.string
import com.woocommerce.android.di.ViewModelAssistedFactory
import com.woocommerce.android.model.Product
import com.woocommerce.android.tools.NetworkStatus
import com.woocommerce.android.tools.SelectedSite
import com.woocommerce.android.util.CoroutineDispatchers
import com.woocommerce.android.viewmodel.LiveDataDelegate
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.Exit
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.ShowDiscardDialog
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.ShowSnackbar
import com.woocommerce.android.viewmodel.SavedStateWithArgs
import com.woocommerce.android.viewmodel.ScopedViewModel
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.launch

class ProductInventoryViewModel @AssistedInject constructor(
    @Assisted savedState: SavedStateWithArgs,
    dispatchers: CoroutineDispatchers,
    private val selectedSite: SelectedSite,
    private val productRepository: ProductDetailRepository,
    private val networkStatus: NetworkStatus
) : ScopedViewModel(savedState, dispatchers) {
    val viewStateLiveData = LiveDataDelegate(savedState, ViewState())
    private var viewState by viewStateLiveData

    fun start(remoteProductId: Long) {
        loadProduct(remoteProductId)
    }

    override fun onCleared() {
        super.onCleared()
        productRepository.onCleanup()
    }

    /**
     * Update all product inventory fields that are edited by the user
     */
    fun updateProductInventoryDraft(
        sku: String? = null,
        manageStock: Boolean? = null,
        stockStatus: ProductStockStatus? = null,
        soldIndividually: Boolean? = null,
        stockQuantity: String? = null,
        backorderStatus: ProductBackorderStatus? = null
    ) {
        sku?.let {
            if (it != viewState.product?.sku) {
                viewState.product?.sku = it
            }
        }
        manageStock?.let {
            if (it != viewState.product?.manageStock) {
                viewState.product?.manageStock = it
            }
        }
        stockStatus?.let {
            if (it != viewState.product?.stockStatus) {
                viewState.product?.stockStatus = it
            }
        }
        soldIndividually?.let {
            if (it != viewState.product?.soldIndividually) {
                viewState.product?.soldIndividually = it
            }
        }
        stockQuantity?.let {
            val quantity = it.toInt()
            if (quantity != viewState.product?.stockQuantity) {
                viewState.product?.stockQuantity = quantity
            }
        }
        backorderStatus?.let {
            if (it != viewState.product?.backorderStatus) {
                viewState.product?.backorderStatus = it
            }
        }

        viewState.product?.let {
            val isProductUpdated = viewState.storedProduct?.isSameProduct(it) == false
            viewState = viewState.copy(isProductUpdated = isProductUpdated)
        }
    }

    fun onUpdateButtonClicked() {
        viewState.product?.let {
            viewState = viewState.copy(isProgressDialogShown = true)
            launch { updateProduct(it) }
        }
    }

    fun onBackButtonClicked(): Boolean {
        return if (viewState.isProductUpdated == true && viewState.shouldShowDiscardDialog) {
            triggerEvent(ShowDiscardDialog(
                    positiveBtnAction = DialogInterface.OnClickListener { _, _ ->
                        viewState = viewState.copy(shouldShowDiscardDialog = false)
                        triggerEvent(Exit)
                    },
                    negativeBtnAction = DialogInterface.OnClickListener { _, _ ->
                        viewState = viewState.copy(shouldShowDiscardDialog = true)
                    }
            ))
            false
        } else {
            true
        }
    }

    private fun loadProduct(remoteProductId: Long) {
        launch {
            val productInDb = productRepository.getProduct(remoteProductId)
            if (productInDb != null) {
                viewState = viewState.copy(
                        product = productInDb.mergeProduct(viewState.product),
                        storedProduct = productInDb
                )
            }
        }
    }

    private suspend fun updateProduct(product: Product) {
        if (networkStatus.isConnected()) {
            if (productRepository.updateProduct(product)) {
                triggerEvent(ShowSnackbar(string.product_detail_update_product_success))
                viewState = viewState.copy(isProgressDialogShown = false, isProductUpdated = false, product = null)
                loadProduct(product.remoteId)
            } else {
                triggerEvent(ShowSnackbar(string.product_detail_update_product_error))
                viewState = viewState.copy(isProgressDialogShown = false)
            }
        } else {
            triggerEvent(ShowSnackbar(string.offline_error))
            viewState = viewState.copy(isProgressDialogShown = false)
        }
    }

    @Parcelize
    data class ViewState(
        val product: Product? = null,
        val isProgressDialogShown: Boolean? = null,
        val isProductUpdated: Boolean = false,
        val shouldShowDiscardDialog: Boolean = true,
        var storedProduct: Product? = null
    ) : Parcelable

    @AssistedInject.Factory
    interface Factory : ViewModelAssistedFactory<ProductInventoryViewModel>
}
