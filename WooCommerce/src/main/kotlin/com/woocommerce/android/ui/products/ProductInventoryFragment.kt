package com.woocommerce.android.ui.products

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import com.woocommerce.android.R
import com.woocommerce.android.RequestCodes
import com.woocommerce.android.analytics.AnalyticsTracker
import com.woocommerce.android.extensions.collapse
import com.woocommerce.android.extensions.expand
import com.woocommerce.android.extensions.navigateBackWithResult
import com.woocommerce.android.extensions.takeIfNotEqualTo
import com.woocommerce.android.ui.base.BaseFragment
import com.woocommerce.android.ui.base.UIMessageResolver
import com.woocommerce.android.ui.dialog.CustomDiscardDialog
import com.woocommerce.android.ui.main.MainActivity.Companion.BackPressListener
import com.woocommerce.android.ui.products.ProductInventorySelectorDialog.ProductInventorySelectorDialogListener
import com.woocommerce.android.ui.products.ProductInventoryViewModel.ViewState
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.Exit
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.NavigateBackWithResult
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.ShowDiscardDialog
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.ShowSnackbar
import com.woocommerce.android.viewmodel.ViewModelFactory
import kotlinx.android.synthetic.main.fragment_product_inventory.*
import org.wordpress.android.util.ActivityUtils
import javax.inject.Inject

class ProductInventoryFragment : BaseFragment(), ProductInventorySelectorDialogListener,
        BackPressListener {
    @Inject lateinit var viewModelFactory: ViewModelFactory
    @Inject lateinit var uiMessageResolver: UIMessageResolver

    private val viewModel: ProductInventoryViewModel by viewModels { viewModelFactory }

    private var productBackOrderSelectorDialog: ProductInventorySelectorDialog? = null
    private var productStockStatusSelectorDialog: ProductInventorySelectorDialog? = null

    private var publishMenuItem: MenuItem? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        setHasOptionsMenu(true)
        return inflater.inflate(R.layout.fragment_product_inventory, container, false)
    }

    override fun onPause() {
        super.onPause()
        productBackOrderSelectorDialog?.dismiss()
        productBackOrderSelectorDialog = null

        productStockStatusSelectorDialog?.dismiss()
        productStockStatusSelectorDialog = null
    }

    override fun onResume() {
        super.onResume()
        AnalyticsTracker.trackViewShown(this)
    }

    override fun onStop() {
        super.onStop()
        CustomDiscardDialog.onCleared()
        activity?.let {
            ActivityUtils.hideKeyboard(it)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupObservers(viewModel)
    }

    override fun getFragmentTitle() = getString(R.string.product_inventory)

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        menu.clear()
        inflater.inflate(R.menu.menu_done, menu)
        publishMenuItem = menu.findItem(R.id.menu_done)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_done -> {
                // TODO: add track event for click
                ActivityUtils.hideKeyboard(activity)
                viewModel.onDoneButtonClicked()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupObservers(viewModel: ProductInventoryViewModel) {
        viewModel.viewStateLiveData.observe(viewLifecycleOwner) { old, new ->
            new.productInventoryParameters?.takeIfNotEqualTo(old?.productInventoryParameters) { showProduct(new) }
            new.isProductUpdated.takeIfNotEqualTo(old?.isProductUpdated) { showUpdateProductAction(it) }
            new.skuErrorMessage?.takeIfNotEqualTo(old?.skuErrorMessage) { displaySkuError(it) }
        }

        viewModel.event.observe(viewLifecycleOwner, Observer { event ->
            when (event) {
                is ShowSnackbar -> uiMessageResolver.showSnack(event.message)
                is ShowDiscardDialog -> CustomDiscardDialog.showDiscardDialog(
                        requireActivity(),
                        event.positiveBtnAction,
                        event.negativeBtnAction
                )
                is Exit -> requireActivity().onBackPressed()
                is NavigateBackWithResult -> navigateBackWithResult(event.args)
            }
        })
    }

    private fun showProduct(productData: ViewState) {
        if (!isAdded) return

        val product = requireNotNull(productData.productInventoryParameters)
        if (product.sku.isNotEmpty()) {
            with(product_sku) {
                setText(product.sku)
                setOnTextChangedListener {
                    viewModel.updateProductInventoryDraft(sku = it.toString())
                    viewModel.onSkuChanged(it.toString())
                }
            }
        }

        val manageStock = product.manageStock
        enableManageStockStatus(manageStock)
        with(manageStock_switch) {
            isChecked = manageStock
            setOnCheckedChangeListener { _, b ->
                enableManageStockStatus(b)
                viewModel.updateProductInventoryDraft(manageStock = b)
            }
        }

        with(product_stock_quantity) {
            setText(product.stockQuantity.toString())
            setOnTextChangedListener {
                if (it.toString().isNotEmpty()) {
                    viewModel.updateProductInventoryDraft(stockQuantity = it.toString())
                }
            }
        }

        with(edit_product_backorders) {
            setText(ProductBackorderStatus.backordersToDisplayString(requireContext(), product.backOrderStatus))
            setClickListener {
                productBackOrderSelectorDialog = ProductInventorySelectorDialog.newInstance(
                        this@ProductInventoryFragment, RequestCodes.PRODUCT_INVENTORY_BACKORDERS,
                        getString(R.string.product_backorders), ProductBackorderStatus.toMap(requireContext()),
                        edit_product_backorders.getText()
                ).also { it.show(parentFragmentManager, ProductInventorySelectorDialog.TAG) }
            }
        }

        with(edit_product_stock_status) {
            setText(ProductStockStatus.stockStatusToDisplayString(requireContext(), product.stockStatus))
            setClickListener {
                productStockStatusSelectorDialog = ProductInventorySelectorDialog.newInstance(
                        this@ProductInventoryFragment, RequestCodes.PRODUCT_INVENTORY_STOCK_STATUS,
                        getString(R.string.product_stock_status), ProductStockStatus.toMap(requireContext()),
                        edit_product_stock_status.getText()
                ).also { it.show(parentFragmentManager, ProductInventorySelectorDialog.TAG) }
            }
        }

        with(soldIndividually_switch) {
            isChecked = product.soldIndividually
            setOnCheckedChangeListener { _, b ->
                viewModel.updateProductInventoryDraft(soldIndividually = b)
            }
        }
    }

    private fun enableManageStockStatus(manageStock: Boolean) {
        if (manageStock) {
            edit_product_stock_status.visibility = View.GONE
            manageStock_morePanel.expand()
        } else {
            edit_product_stock_status.visibility = View.VISIBLE
            manageStock_morePanel.collapse()
        }
    }

    private fun showUpdateProductAction(show: Boolean) {
        view?.post { publishMenuItem?.isVisible = show }
    }

    private fun displaySkuError(messageId: Int) {
        if (messageId != 0) {
            product_sku.setError(getString(messageId))
            publishMenuItem?.isEnabled = false
        } else {
            product_sku.clearError()
            publishMenuItem?.isEnabled = true
        }
    }

    private fun navigateBackWithResult(bundle: Bundle) {
        requireActivity().navigateBackWithResult(
                RequestCodes.PRODUCT_INVENTORY,
                bundle,
                R.id.nav_host_fragment_main,
                R.id.productDetailFragment
        )
    }

    override fun onProductInventoryItemSelected(resultCode: Int, selectedItem: String?) {
        when (resultCode) {
            RequestCodes.PRODUCT_INVENTORY_BACKORDERS -> {
                selectedItem?.let {
                    edit_product_backorders.setText(getString(ProductBackorderStatus.toStringResource(it)))
                    viewModel.updateProductInventoryDraft(backorderStatus = ProductBackorderStatus.fromString(it))
                }
            }
            RequestCodes.PRODUCT_INVENTORY_STOCK_STATUS -> {
                selectedItem?.let {
                    edit_product_stock_status.setText(getString(ProductStockStatus.toStringResource(it)))
                    viewModel.updateProductInventoryDraft(stockStatus = ProductStockStatus.fromString(it))
                }
            }
        }
    }

    override fun onRequestAllowBackPress(): Boolean {
        return viewModel.onBackButtonClicked()
    }
}
