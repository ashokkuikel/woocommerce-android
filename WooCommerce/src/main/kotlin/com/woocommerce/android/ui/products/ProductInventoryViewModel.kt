package com.woocommerce.android.ui.products

import android.content.DialogInterface
import android.os.Bundle
import android.os.Parcelable
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import com.woocommerce.android.R
import com.woocommerce.android.di.ViewModelAssistedFactory
import com.woocommerce.android.util.CoroutineDispatchers
import com.woocommerce.android.viewmodel.LiveDataDelegate
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.Exit
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.NavigateBackWithResult
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.ShowDiscardDialog
import com.woocommerce.android.viewmodel.SavedStateWithArgs
import com.woocommerce.android.viewmodel.ScopedViewModel
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ProductInventoryViewModel @AssistedInject constructor(
    @Assisted savedState: SavedStateWithArgs,
    dispatchers: CoroutineDispatchers,
    private val productRepository: ProductInventoryRepository
) : ScopedViewModel(savedState, dispatchers) {
    companion object {
        private const val SEARCH_TYPING_DELAY_MS = 500L
        const val BUNDLE_PRODUCT_INVENTORY_MODEL = "arg_product_inventory_model"
    }

    val viewStateLiveData = LiveDataDelegate(savedState, ViewState())
    private var viewState by viewStateLiveData

    private var skuVerificationJob: Job? = null

    private val navArgs: ProductInventoryFragmentArgs by savedState.navArgs()

    init {
        loadProduct(navArgs.remoteProductId)
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
            if (it != viewState.productInventoryParameters?.sku) {
                viewState.productInventoryParameters?.sku = it
            }
        }
        manageStock?.let {
            if (it != viewState.productInventoryParameters?.manageStock) {
                viewState.productInventoryParameters?.manageStock = it
            }
        }
        stockStatus?.let {
            if (it != viewState.productInventoryParameters?.stockStatus) {
                viewState.productInventoryParameters?.stockStatus = it
            }
        }
        soldIndividually?.let {
            if (it != viewState.productInventoryParameters?.soldIndividually) {
                viewState.productInventoryParameters?.soldIndividually = it
            }
        }
        stockQuantity?.let {
            val quantity = it.toInt()
            if (quantity != viewState.productInventoryParameters?.stockQuantity) {
                viewState.productInventoryParameters?.stockQuantity = quantity
            }
        }
        backorderStatus?.let {
            if (it != viewState.productInventoryParameters?.backOrderStatus) {
                viewState.productInventoryParameters?.backOrderStatus = it
            }
        }
        viewState.productInventoryParameters?.let {
            val isProductUpdated = viewState.storedProductInventoryParameters?.isSameInventory(it) == false
            viewState = viewState.copy(isProductUpdated = isProductUpdated)
        }
    }

    fun onSkuChanged(sku: String) {
        // verify if the sku exists only if the text entered by the user does not match the sku stored locally
        if (sku.length > 2 &&
                viewState.storedProductInventoryParameters?.sku != viewState.productInventoryParameters?.sku) {
            // reset the error message when the user starts typing again
            viewState = viewState.copy(skuErrorMessage = 0)

            // cancel any existing verification search, then start a new one after a brief delay
            // so we don't actually perform the fetch until the user stops typing
            skuVerificationJob?.cancel()
            skuVerificationJob = launch {
                delay(SEARCH_TYPING_DELAY_MS)

                // check if sku is available from local cache
                if (productRepository.geProductExistsBySku(sku)) {
                    viewState = viewState.copy(skuErrorMessage = R.string.product_inventory_update_sku_error)
                } else {
                    verifyProductExistsBySkuRemotely(sku)
                }
            }
        }
    }

    fun onBackButtonClicked(): Boolean {
        return if (viewState.isProductUpdated && viewState.shouldShowDiscardDialog) {
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

    fun onDoneButtonClicked() {
        viewState = viewState.copy(shouldShowDiscardDialog = false)

        val bundle = Bundle()
        bundle.putParcelable(BUNDLE_PRODUCT_INVENTORY_MODEL, viewState.productInventoryParameters)
        triggerEvent(NavigateBackWithResult(bundle))
    }

    private fun loadProduct(remoteProductId: Long) {
        launch {
            val productInDb = productRepository.getProduct(remoteProductId)
            if (productInDb != null) {
                val storedProductInventoryParameters = ProductInventoryParameters(
                        productInDb.sku, productInDb.manageStock, productInDb.stockStatus,
                        productInDb.stockQuantity, productInDb.backorderStatus, productInDb.soldIndividually
                )
                val updatedProductInventoryParameters =
                        fetchUpdatedProductInventoryParameters(storedProductInventoryParameters)
                viewState = viewState.copy(
                        productInventoryParameters = updatedProductInventoryParameters,
                        storedProductInventoryParameters = storedProductInventoryParameters
                )
            }
        }
    }

    private fun fetchUpdatedProductInventoryParameters(
        storedProductInventoryParameters: ProductInventoryParameters
    ): ProductInventoryParameters {
        return if (viewState.isProductUpdated) {
            storedProductInventoryParameters.merge(viewState.productInventoryParameters)
        } else storedProductInventoryParameters.copy()
    }

    private suspend fun verifyProductExistsBySkuRemotely(sku: String) {
        // if the sku is not available display error
        val isSkuAvailable = productRepository.verifySkuAvailability(sku)
        val skuErrorMessage = if (isSkuAvailable == false) {
            R.string.product_inventory_update_sku_error
        } else 0
        viewState = viewState.copy(skuErrorMessage = skuErrorMessage)
    }

    @Parcelize
    data class ViewState(
        val isProductUpdated: Boolean = false,
        val shouldShowDiscardDialog: Boolean = true,
        val productInventoryParameters: ProductInventoryParameters? = null,
        val storedProductInventoryParameters: ProductInventoryParameters? = null,
        val skuErrorMessage: Int? = null
    ) : Parcelable

    @Parcelize
    data class ProductInventoryParameters(
        var sku: String,
        var manageStock: Boolean,
        var stockStatus: ProductStockStatus,
        var stockQuantity: Int,
        var backOrderStatus: ProductBackorderStatus,
        var soldIndividually: Boolean
    ) : Parcelable {
        fun merge(newInventoryParameters: ProductInventoryParameters?): ProductInventoryParameters {
            return newInventoryParameters?.let { updatedInventory ->
                this.copy().apply {
                    if (sku != updatedInventory.sku) {
                        sku = updatedInventory.sku
                    }
                    if (manageStock != updatedInventory.manageStock) {
                        manageStock = updatedInventory.manageStock
                    }
                    if (stockStatus != updatedInventory.stockStatus) {
                        stockStatus = updatedInventory.stockStatus
                    }
                    if (stockQuantity != updatedInventory.stockQuantity) {
                        stockQuantity = updatedInventory.stockQuantity
                    }
                    if (backOrderStatus != updatedInventory.backOrderStatus) {
                        backOrderStatus = updatedInventory.backOrderStatus
                    }
                    if (soldIndividually != updatedInventory.soldIndividually) {
                        soldIndividually = updatedInventory.soldIndividually
                    }
                }
            } ?: this.copy()
        }
        fun isSameInventory(productInventoryParameters: ProductInventoryParameters): Boolean {
            return sku == productInventoryParameters.sku &&
                    stockQuantity == productInventoryParameters.stockQuantity &&
                    stockStatus == productInventoryParameters.stockStatus &&
                    manageStock == productInventoryParameters.manageStock &&
                    backOrderStatus == productInventoryParameters.backOrderStatus &&
                    soldIndividually == productInventoryParameters.soldIndividually
        }
    }

    @AssistedInject.Factory
    interface Factory : ViewModelAssistedFactory<ProductInventoryViewModel>
}
