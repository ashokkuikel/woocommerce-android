package com.woocommerce.android.ui.products

import androidx.lifecycle.MutableLiveData
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.spy
import com.nhaarman.mockitokotlin2.times
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import com.woocommerce.android.R
import com.woocommerce.android.extensions.takeIfNotEqualTo
import com.woocommerce.android.tools.NetworkStatus
import com.woocommerce.android.tools.SelectedSite
import com.woocommerce.android.ui.products.ProductDetailViewModel.ViewState
import com.woocommerce.android.util.CoroutineDispatchers
import com.woocommerce.android.util.CurrencyFormatter
import com.woocommerce.android.viewmodel.BaseUnitTest
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.ShowSnackbar
import com.woocommerce.android.viewmodel.SavedStateWithArgs
import com.woocommerce.android.viewmodel.test
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.wordpress.android.fluxc.model.SiteModel
import org.wordpress.android.fluxc.model.WCProductSettingsModel
import org.wordpress.android.fluxc.model.WCSettingsModel
import org.wordpress.android.fluxc.store.WooCommerceStore
import java.math.BigDecimal

class ProductDetailViewModelTest : BaseUnitTest() {
    private val wooCommerceStore: WooCommerceStore = mock()
    private val selectedSite: SelectedSite = mock()
    private val networkStatus: NetworkStatus = mock()
    private val productRepository: ProductDetailRepository = mock()
    private val currencyFormatter: CurrencyFormatter = mock {
        on(it.formatCurrency(any<BigDecimal>(), any(), any())).thenAnswer { i -> "${i.arguments[1]}${i.arguments[0]}" }
    }
    private val savedState: SavedStateWithArgs = mock()

    private val coroutineDispatchers = CoroutineDispatchers(
            Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)
    private val product = ProductTestUtils.generateProduct()
    private val productRemoteId = product.remoteId
    private lateinit var viewModel: ProductDetailViewModel

    private val productWithParameters = ViewState(
            product,
            "10kg",
            "1 x 2 x 3 cm",
            "CZK20.00",
            "CZK10.00",
            "CZK30.00",
            false,
            uploadingImageUris = emptyList(),
            storedProduct = product
    )

    @Before
    fun setup() {
        doReturn(MutableLiveData(ViewState())).whenever(savedState).getLiveData<ViewState>(any(), any())

        viewModel = spy(
                ProductDetailViewModel(
                        savedState,
                        coroutineDispatchers,
                        selectedSite,
                        productRepository,
                        networkStatus,
                        currencyFormatter,
                        wooCommerceStore
                )
        )
        val prodSettings = WCProductSettingsModel(0).apply {
            dimensionUnit = "cm"
            weightUnit = "kg"
        }
        val siteSettings = mock<WCSettingsModel> {
            on(it.currencyCode).thenReturn("CZK")
        }

        doReturn(SiteModel()).whenever(selectedSite).get()
        doReturn(true).whenever(networkStatus).isConnected()
        doReturn(prodSettings).whenever(wooCommerceStore).getProductSettings(any())
        doReturn(siteSettings).whenever(wooCommerceStore).getSiteSettings(any())
    }

    @Test
    fun `Displays the product detail view correctly`() {
        doReturn(product).whenever(productRepository).getProduct(any())

        var productData: ViewState? = null
        viewModel.viewStateData.observeForever { _, new -> productData = new }

        assertThat(productData).isEqualTo(ViewState())

        viewModel.start(productRemoteId)

        assertThat(productData).isEqualTo(productWithParameters)
    }

    @Test
    fun `Display error message on fetch product error`() = test {
        whenever(productRepository.fetchProduct(productRemoteId)).thenReturn(null)
        whenever(productRepository.getProduct(productRemoteId)).thenReturn(null)

        var snackbar: ShowSnackbar? = null
        viewModel.event.observeForever {
            if (it is ShowSnackbar) snackbar = it
        }

        viewModel.start(productRemoteId)

        verify(productRepository, times(1)).fetchProduct(productRemoteId)

        assertThat(snackbar).isEqualTo(ShowSnackbar(R.string.product_detail_fetch_product_error))
    }

    @Test
    fun `Do not fetch product from api when not connected`() = test {
        doReturn(product).whenever(productRepository).getProduct(any())
        doReturn(false).whenever(networkStatus).isConnected()

        var snackbar: ShowSnackbar? = null
        viewModel.event.observeForever {
            if (it is ShowSnackbar) snackbar = it
        }

        viewModel.start(productRemoteId)

        verify(productRepository, times(1)).getProduct(productRemoteId)
        verify(productRepository, times(0)).fetchProduct(any())

        assertThat(snackbar).isEqualTo(ShowSnackbar(R.string.offline_error))
    }

    @Test
    fun `Shows and hides product detail skeleton correctly`() = test {
        doReturn(null).whenever(productRepository).getProduct(any())

        val isSkeletonShown = ArrayList<Boolean>()
        viewModel.viewStateData.observeForever {
            old, new -> new.isSkeletonShown?.takeIfNotEqualTo(old?.isSkeletonShown) { isSkeletonShown.add(it) }
        }

        viewModel.start(productRemoteId)

        assertThat(isSkeletonShown).containsExactly(true, false)
    }

    @Test
    fun `Displays the updated product detail view correctly`() {
        doReturn(product).whenever(productRepository).getProduct(any())

        var productData: ViewState? = null
        viewModel.viewStateData.observeForever { _, new -> productData = new }

        assertThat(productData).isEqualTo(ViewState())

        viewModel.start(productRemoteId)
        assertThat(productData).isEqualTo(productWithParameters)

        val updatedDescription = "Updated product description"
        viewModel.updateProductDraft(updatedDescription)

        viewModel.start(productRemoteId)
        assertThat(productData?.product?.description).isEqualTo(updatedDescription)
    }

    @Test
    fun `Displays update menu action if product is edited`() {
        doReturn(product).whenever(productRepository).getProduct(any())

        var productData: ViewState? = null
        viewModel.viewStateData.observeForever { _, new -> productData = new }

        viewModel.start(productRemoteId)
        assertThat(productData?.isProductUpdated).isNull()

        val updatedDescription = "Updated product description"
        viewModel.updateProductDraft(updatedDescription)

        viewModel.start(productRemoteId)
        assertThat(productData?.isProductUpdated).isTrue()
    }

    @Test
    fun `Displays progress dialog when product is edited`() = test {
        doReturn(product).whenever(productRepository).getProduct(any())
        doReturn(false).whenever(productRepository).updateProduct(any())

        val isProgressDialogShown = ArrayList<Boolean>()
        viewModel.viewStateData.observeForever { old, new ->
            new.isProgressDialogShown?.takeIfNotEqualTo(old?.isProgressDialogShown) {
                isProgressDialogShown.add(it)
            } }

        viewModel.start(productRemoteId)
        viewModel.onUpdateButtonClicked()

        assertThat(isProgressDialogShown).containsExactly(true, false)
    }

    @Test
    fun `Do not update product when not connected`() = test {
        doReturn(product).whenever(productRepository).getProduct(any())
        doReturn(false).whenever(networkStatus).isConnected()

        var snackbar: ShowSnackbar? = null
        viewModel.event.observeForever {
            if (it is ShowSnackbar) snackbar = it
        }

        var productData: ViewState? = null
        viewModel.viewStateData.observeForever { _, new -> productData = new }

        viewModel.start(productRemoteId)
        viewModel.onUpdateButtonClicked()

        verify(productRepository, times(0)).updateProduct(any())
        assertThat(snackbar).isEqualTo(ShowSnackbar(R.string.offline_error))
        assertThat(productData?.isProgressDialogShown).isFalse()
    }

    @Test
    fun `Display error message on update product error`() = test {
        doReturn(product).whenever(productRepository).getProduct(any())
        doReturn(false).whenever(productRepository).updateProduct(any())

        var snackbar: ShowSnackbar? = null
        viewModel.event.observeForever {
            if (it is ShowSnackbar) snackbar = it
        }

        var productData: ViewState? = null
        viewModel.viewStateData.observeForever { _, new -> productData = new }

        viewModel.start(productRemoteId)
        viewModel.onUpdateButtonClicked()

        verify(productRepository, times(1)).updateProduct(any())
        assertThat(snackbar).isEqualTo(ShowSnackbar(R.string.product_detail_update_product_error))
        assertThat(productData?.isProgressDialogShown).isFalse()
    }

    @Test
    fun `Display success message on update product success`() = test {
        doReturn(product).whenever(productRepository).getProduct(any())
        doReturn(true).whenever(productRepository).updateProduct(any())

        var snackbar: ShowSnackbar? = null
        viewModel.event.observeForever {
            if (it is ShowSnackbar) snackbar = it
        }

        var productData: ViewState? = null
        viewModel.viewStateData.observeForever { _, new -> productData = new }

        viewModel.start(productRemoteId)
        viewModel.onUpdateButtonClicked()

        verify(productRepository, times(1)).updateProduct(any())
        verify(productRepository, times(2)).getProduct(productRemoteId)
        verify(productRepository, times(1)).fetchProduct(any())

        assertThat(snackbar).isEqualTo(ShowSnackbar(R.string.product_detail_update_product_success))
        assertThat(productData?.isProgressDialogShown).isFalse()
        assertThat(productData?.isProductUpdated).isFalse()
        assertThat(productData?.product).isEqualTo(product)
    }

    @Test
    fun `Displays discard dialog if product is edited and back button is pressed`() = test {
        doReturn(product).whenever(productRepository).getProduct(any())

        val shouldShowDiscard = ArrayList<Boolean>()
        viewModel.viewStateData.observeForever { old, new ->
            new.shouldShowDiscardDialog.takeIfNotEqualTo(old?.shouldShowDiscardDialog) {
                shouldShowDiscard.add(it)
            } }

        viewModel.start(productRemoteId)

        val updatedDescription = "Updated product description"
        viewModel.updateProductDraft(updatedDescription)
        viewModel.onBackButtonClicked()

        assertThat(shouldShowDiscard).containsExactly(true)
    }
}
