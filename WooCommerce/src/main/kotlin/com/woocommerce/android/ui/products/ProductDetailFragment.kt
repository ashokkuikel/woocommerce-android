package com.woocommerce.android.ui.products

import android.Manifest.permission
import android.app.Activity
import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProviders
import androidx.navigation.fragment.navArgs
import com.woocommerce.android.R
import com.woocommerce.android.analytics.AnalyticsTracker
import com.woocommerce.android.analytics.AnalyticsTracker.Stat
import com.woocommerce.android.analytics.AnalyticsTracker.Stat.PRODUCT_DETAIL_IMAGE_TAPPED
import com.woocommerce.android.analytics.AnalyticsTracker.Stat.PRODUCT_DETAIL_SHARE_BUTTON_TAPPED
import com.woocommerce.android.analytics.AnalyticsTracker.Stat.PRODUCT_DETAIL_VIEW_AFFILIATE_TAPPED
import com.woocommerce.android.analytics.AnalyticsTracker.Stat.PRODUCT_DETAIL_VIEW_EXTERNAL_TAPPED
import com.woocommerce.android.model.Product
import com.woocommerce.android.ui.base.BaseFragment
import com.woocommerce.android.ui.base.UIMessageResolver
import com.woocommerce.android.ui.imageviewer.ImageViewerActivity
import com.woocommerce.android.ui.products.ProductDetailViewModel.ProductWithParameters
import com.woocommerce.android.ui.products.ProductType.EXTERNAL
import com.woocommerce.android.ui.products.ProductType.GROUPED
import com.woocommerce.android.ui.products.ProductType.VARIABLE
import com.woocommerce.android.util.FeatureFlag
import com.woocommerce.android.util.StringUtils
import com.woocommerce.android.util.WooPermissionUtils
import com.woocommerce.android.widgets.SkeletonView
import com.woocommerce.android.widgets.WCProductImageGalleryView.OnGalleryImageClickListener
import dagger.android.support.AndroidSupportInjection
import kotlinx.android.synthetic.main.fragment_product_detail.*
import org.wordpress.android.fluxc.model.WCProductImageModel
import org.wordpress.android.util.DisplayUtils
import org.wordpress.android.util.HtmlUtils
import javax.inject.Inject

class ProductDetailFragment : BaseFragment(), OnGalleryImageClickListener {
    companion object {
        private const val MENU_ID_CHOOSE_PHOTO = 2
        private const val MENU_ID_CAPTURE_PHOTO = 3

        private const val REQUEST_CODE_CHOOSE_PHOTO = Activity.RESULT_FIRST_USER
        private const val REQUEST_CODE_CAPTURE_PHOTO = REQUEST_CODE_CHOOSE_PHOTO + 1
    }

    private enum class DetailCard {
        Primary,
        PricingAndInventory,
        Inventory,
        PurchaseDetails
    }

    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory
    @Inject lateinit var uiMessageResolver: UIMessageResolver

    private lateinit var viewModel: ProductDetailViewModel

    private var productTitle = ""
    private var productImageUrl: String? = null
    private var isVariation = false
    private var imageHeight = 0
    private val skeletonView = SkeletonView()

    private val navArgs: ProductDetailFragmentArgs by navArgs()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        return inflater.inflate(R.layout.fragment_product_detail, container, false)
    }

    override fun onAttach(context: Context?) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
    }

    override fun onDestroyView() {
        // hide the skeleton view if fragment is destroyed
        skeletonView.hide()
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        AnalyticsTracker.trackViewShown(this)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeViewModel(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        viewModel.saveState(outState)
    }

    private fun initializeViewModel(savedInstanceState: Bundle?) {
        viewModel = ViewModelProviders.of(this, viewModelFactory).get(ProductDetailViewModel::class.java).also {
            setupObservers(it)
        }

        viewModel.start(navArgs.remoteProductId, savedInstanceState)
    }

    private fun setupObservers(viewModel: ProductDetailViewModel) {
        viewModel.isSkeletonShown.observe(this, Observer {
            showSkeleton(it)
        })

        viewModel.productData.observe(this, Observer {
            showProduct(it)
        })

        viewModel.shareProduct.observe(this, Observer {
            shareProduct(it)
        })

        viewModel.chooseProductImage.observe(this, Observer {
            chooseProductImage()
        })

        viewModel.showSnackbarMessage.observe(this, Observer {
            uiMessageResolver.showSnack(it)
        })

        viewModel.isUploadingProductImage.observe(this, Observer {
            showUploadImageProgress(it)
        })

        viewModel.exit.observe(this, Observer {
            activity?.onBackPressed()
        })
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        // make image height a percentage of screen height, adjusting for landscape
        val displayHeight = DisplayUtils.getDisplayPixelHeight(activity!!)
        val multiplier = if (DisplayUtils.isLandscape(activity!!)) 0.5f else 0.3f
        imageHeight = (displayHeight * multiplier).toInt()
        imageGallery.layoutParams.height = imageHeight
    }

    override fun onCreateOptionsMenu(menu: Menu?, inflater: MenuInflater?) {
        menu?.clear()
        inflater?.inflate(R.menu.menu_share, menu)
        if (FeatureFlag.PRODUCT_IMAGE_CHOOSER.isEnabled()) {
            menu?.add(Menu.NONE, MENU_ID_CHOOSE_PHOTO, Menu.NONE, R.string.product_change_image)
            menu?.add(Menu.NONE, MENU_ID_CAPTURE_PHOTO, Menu.NONE, R.string.product_capture_image)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        return when (item?.itemId) {
            R.id.menu_share -> {
                AnalyticsTracker.track(PRODUCT_DETAIL_SHARE_BUTTON_TAPPED)
                viewModel.onShareButtonClicked()
                true
            }
            MENU_ID_CHOOSE_PHOTO -> {
                // TODO: analytics
                viewModel.onChooseImageClicked()
                true
            }
            MENU_ID_CAPTURE_PHOTO -> {
                captureProduceImage()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSkeleton(show: Boolean) {
        if (show) {
            skeletonView.show(productDetail_root, R.layout.skeleton_product_detail, delayed = true)
            skeletonView.findViewById(R.id.productImage_Skeleton)?.layoutParams?.height = imageHeight
        } else {
            skeletonView.hide()
        }
    }

    override fun getFragmentTitle() = productTitle

    private fun showProduct(productData: ProductWithParameters) {
        if (!isAdded) return

        val product = productData.product
        productTitle = when (product.type) {
            EXTERNAL -> getString(R.string.product_name_external, product.name)
            GROUPED -> getString(R.string.product_name_grouped, product.name)
            VARIABLE -> getString(R.string.product_name_variable, product.name)
            else -> {
                if (product.isVirtual) {
                    getString(R.string.product_name_virtual, product.name)
                } else {
                    product.name
                }
            }
        }

        updateActivityTitle()
        imageGallery.showProductImages(product, this)

        isVariation = product.type == ProductType.VARIATION

        // show status badge for unpublished products
        product.status?.let { status ->
            if (status != ProductStatus.PUBLISH) {
                frameStatusBadge.visibility = View.VISIBLE
                textStatusBadge.text = status.toString(activity!!)
            }
        }

        addPrimaryCard(productData)
        addPricingAndInventoryCard(productData)
        addPurchaseDetailsCard(productData)
    }

    private fun addPrimaryCard(productData: ProductWithParameters) {
        val product = productData.product

        addPropertyView(DetailCard.Primary, R.string.product_name, productTitle, LinearLayout.VERTICAL)

        // we don't show total sales for variations because they're always zero
        if (!isVariation) {
            addPropertyView(
                    DetailCard.Primary,
                    R.string.product_total_orders,
                    StringUtils.formatCount(product.totalSales)
            )
        }

        // we don't show reviews for variations because they're always empty
        if (!isVariation && product.reviewsAllowed) {
            addPropertyView(
                    DetailCard.Primary,
                    R.string.product_reviews,
                    StringUtils.formatCount(product.ratingCount)
            )?.setRating(product.averageRating)
        }

        addLinkView(
                DetailCard.Primary,
                R.string.product_view_in_store,
                product.permalink,
                PRODUCT_DETAIL_VIEW_EXTERNAL_TAPPED
        )
        addLinkView(
                DetailCard.Primary,
                R.string.product_view_affiliate,
                product.externalUrl,
                PRODUCT_DETAIL_VIEW_AFFILIATE_TAPPED
        )
    }

    private fun addPricingAndInventoryCard(productData: ProductWithParameters) {
        val product = productData.product

        // if we have pricing info this card is "Pricing and inventory" otherwise it's just "Inventory"
        val hasPricingInfo = product.price != null || product.salePrice != null || product.taxClass.isNotEmpty()
        val pricingCard = if (hasPricingInfo) DetailCard.PricingAndInventory else DetailCard.Inventory

        if (hasPricingInfo) {
            // when there's a sale price show price & sales price as a group, otherwise show price separately
            if (product.salePrice != null) {
                val group = mapOf(
                        Pair(getString(R.string.product_regular_price), productData.regularPriceWithCurrency),
                        Pair(getString(R.string.product_sale_price), productData.salePriceWithCurrency)
                )
                addPropertyGroup(pricingCard, R.string.product_price, group)
            } else {
                addPropertyView(
                        pricingCard,
                        R.string.product_price,
                        productData.priceWithCurrency,
                        LinearLayout.VERTICAL
                )
            }
        }

        // show stock properties as a group if stock management is enabled, otherwise show sku separately
        if (product.manageStock) {
            val group = mapOf(
                    Pair(getString(R.string.product_stock_status), stockStatusToDisplayString(product.stockStatus)),
                    Pair(getString(R.string.product_backorders), backordersToDisplayString(product.backorderStatus)),
                    Pair(getString(R.string.product_stock_quantity), StringUtils.formatCount(product.stockQuantity)),
                    Pair(getString(R.string.product_sku), product.sku)
            )
            addPropertyGroup(pricingCard, R.string.product_inventory, group)
        } else {
            addPropertyView(pricingCard, R.string.product_sku, product.sku, LinearLayout.VERTICAL)
        }
    }

    private fun addPurchaseDetailsCard(productData: ProductWithParameters) {
        val product = productData.product

        val shippingGroup = mapOf(
                Pair(getString(R.string.product_weight), productData.weightWithUnits),
                Pair(getString(R.string.product_size), productData.sizeWithUnits),
                Pair(getString(R.string.product_shipping_class), product.shippingClass)
        )
        addPropertyGroup(DetailCard.PurchaseDetails, R.string.product_shipping, shippingGroup)

        if (product.isDownloadable) {
            val limit = if (product.downloadLimit > 0) String.format(
                    getString(R.string.product_download_limit_count),
                    product.downloadLimit
            ) else ""
            val expiry = if (product.downloadExpiry > 0) String.format(
                    getString(R.string.product_download_expiry_days),
                    product.downloadExpiry
            ) else ""

            val downloadGroup = mapOf(
                    Pair(getString(R.string.product_downloadable_files), product.fileCount.toString()),
                    Pair(getString(R.string.product_download_limit), limit),
                    Pair(getString(R.string.product_download_expiry), expiry)
            )
            addPropertyGroup(DetailCard.PurchaseDetails, R.string.product_downloads, downloadGroup)
        }

        if (product.purchaseNote.isNotBlank()) {
            addReadMoreView(
                    DetailCard.PurchaseDetails,
                    R.string.product_purchase_note,
                    product.purchaseNote,
                    2
            )
        }
    }

    /**
     * Adds a property card to the current view if it doesn't already exist, then adds the property & value
     * to the card if they don't already exist - this enables us to dynamically build the product detail and
     * more easily move things around.
     *
     * ex: addPropertyView(DetailCard.Pricing, R.string.product_price, product.price) will add the Pricing card if it
     * doesn't exist, and then add the product price caption and property to the card - but if the property
     * is empty, nothing gets added.
     */
    private fun addPropertyView(
        card: DetailCard,
        @StringRes propertyNameId: Int,
        propertyValue: String,
        orientation: Int = LinearLayout.HORIZONTAL
    ): WCProductPropertyView? {
        return addPropertyView(card, getString(propertyNameId), propertyValue, orientation)
    }

    private fun addPropertyView(
        card: DetailCard,
        propertyName: String,
        propertyValue: String,
        orientation: Int = LinearLayout.HORIZONTAL
    ): WCProductPropertyView? {
        if (propertyValue.isBlank()) return null

        // locate the card, add it if it doesn't exist yet
        val cardView = findOrAddCardView(card)

        // locate the linear layout container inside the card
        val container = cardView.findViewById<LinearLayout>(R.id.cardContainerView)

        // locate the existing property view in the container, add it if not found
        val propertyTag = "{$propertyName}_tag"
        var propertyView = container.findViewWithTag<WCProductPropertyView>(propertyTag)
        if (propertyView == null) {
            propertyView = WCProductPropertyView(activity!!)
            propertyView.tag = propertyTag
            container.addView(propertyView)
        }

        // some details, such as product description, contain html which needs to be stripped here
        propertyView.show(orientation, propertyName, HtmlUtils.fastStripHtml(propertyValue).trim())
        return propertyView
    }

    /**
     * Adds a group of related properties as a single property view
     */
    private fun addPropertyGroup(
        card: DetailCard,
        @StringRes groupTitleId: Int,
        properties: Map<String, String>
    ): WCProductPropertyView? {
        var propertyValue = ""
        properties.forEach { property ->
            if (property.value.isNotEmpty()) {
                if (propertyValue.isNotEmpty()) {
                    propertyValue += "\n"
                }
                propertyValue += "${property.key}: ${property.value}"
            }
        }
        return addPropertyView(card, getString(groupTitleId), propertyValue, LinearLayout.VERTICAL)
    }

    /**
     * Adds a property link to the passed card
     */
    private fun addLinkView(
        card: DetailCard,
        @StringRes captionId: Int,
        url: String,
        tracksEvent: Stat
    ): WCProductPropertyLinkView? {
        if (url.isEmpty()) return null

        val caption = getString(captionId)
        val linkViewTag = "${caption}_tag"

        val cardView = findOrAddCardView(card)
        val container = cardView.findViewById<LinearLayout>(R.id.cardContainerView)
        var linkView = container.findViewWithTag<WCProductPropertyLinkView>(linkViewTag)

        if (linkView == null) {
            linkView = WCProductPropertyLinkView(activity!!)
            linkView.tag = linkViewTag
            container.addView(linkView)
        }

        linkView.show(caption, url, tracksEvent)
        return linkView
    }

    /**
     * Adds a "read more" view which limits content to a certain number of lines, and if it goes over
     * a "Read more" button appears
     */
    private fun addReadMoreView(card: DetailCard, @StringRes captionId: Int, content: String, maxLines: Int) {
        val caption = getString(captionId)
        val readMoreTag = "${caption}_read_more_tag"

        val cardView = findOrAddCardView(card)
        val container = cardView.findViewById<LinearLayout>(R.id.cardContainerView)
        var readMoreView = container.findViewWithTag<WCProductPropertyReadMoreView>(readMoreTag)

        if (readMoreView == null) {
            readMoreView = WCProductPropertyReadMoreView(activity!!)
            readMoreView.tag = readMoreTag
            container.addView(readMoreView)
        }

        readMoreView.show(caption, HtmlUtils.fastStripHtml(content), maxLines)
    }

    /**
     * Returns the card view for the passed DetailCard - card will be added if it doesn't already exist
     */
    private fun findOrAddCardView(card: DetailCard): WCProductPropertyCardView {
        val cardTag = "${card.name}_tag"
        productDetail_container.findViewWithTag<WCProductPropertyCardView>(cardTag)?.let {
            return it
        }

        // add a divider above the card if this isn't the first card
        if (card != DetailCard.Primary) {
            addCardDividerView(activity!!)
        }

        val cardView = WCProductPropertyCardView(activity!!)
        cardView.tag = cardTag

        val cardViewCaption: String? = when (card) {
            DetailCard.Primary -> null
            DetailCard.PricingAndInventory -> getString(R.string.product_pricing_and_inventory)
            DetailCard.Inventory -> getString(R.string.product_inventory)
            DetailCard.PurchaseDetails -> getString(R.string.product_purchase_details)
        }

        cardView.show(cardViewCaption)
        productDetail_container.addView(cardView)

        return cardView
    }

    /**
     * Adds a divider between cards
     */
    private fun addCardDividerView(context: Context) {
        val divider = View(context)
        divider.layoutParams = LayoutParams(
                MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.product_detail_card_divider_height)
        )
        divider.setBackgroundColor(ContextCompat.getColor(context, R.color.default_window_background))
        productDetail_container.addView(divider)
    }

    private fun shareProduct(product: Product) {
        val shareIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_SUBJECT, product.name)
            putExtra(Intent.EXTRA_TEXT, product.permalink)
            type = "text/plain"
        }
        val title = resources.getText(R.string.product_share_dialog_title)
        startActivity(Intent.createChooser(shareIntent, title))
    }

    /**
     * returns the product's stock status formatted for display
     */
    private fun stockStatusToDisplayString(status: ProductStockStatus): String {
        return if (status.stringResource != 0) {
            getString(status.stringResource)
        } else {
            status.value
        }
    }

    private fun backordersToDisplayString(status: ProductBackorderStatus): String {
        return if (status.stringResource != 0) {
            getString(status.stringResource)
        } else {
            status.value
        }
    }

    private fun chooseProductImage() {
        // only show the chooser if user already allowed storage permission, otherwise simply request the
        // permission and do nothing else - this will be called again if the user then agrees to allow
        // storage permission
        if (requestStoragePermission()) {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "image/*"
            val chooser = Intent.createChooser(intent, getString(R.string.product_change_image))
            activity?.startActivityFromFragment(this, chooser, REQUEST_CODE_CHOOSE_PHOTO)
        }
    }

    private fun captureProduceImage() {
        if (requestCameraPermission()) {
            viewModel.createCaptureImageIntent(requireActivity())?.let { intent ->
                activity?.startActivityFromFragment(this, intent, REQUEST_CODE_CAPTURE_PHOTO)
            }
        }
    }

    /**
     * Triggered by the viewModel when an image is being uploaded or has finished uploading
     */
    private fun showUploadImageProgress(isUploading: Boolean) {
        // TODO
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_CHOOSE_PHOTO && resultCode == RESULT_OK && data != null) {
            data.data?.let { imageUri ->
                viewModel.uploadProductMedia(navArgs.remoteProductId, imageUri)
            }
        } else if (requestCode == REQUEST_CODE_CAPTURE_PHOTO) {
            viewModel.uploadCapturedImage(navArgs.remoteProductId)
        }
    }

    override fun onGalleryImageClicked(image: WCProductImageModel, imageView: View) {
        AnalyticsTracker.track(PRODUCT_DETAIL_IMAGE_TAPPED)
        ImageViewerActivity.show(
                requireActivity(),
                image.src,
                title = productTitle,
                sharedElement = imageView
        )
    }

    /**
     * Requests storage permission, returns true only if permission is already available
     */
    private fun requestStoragePermission(): Boolean {
        if (!isAdded) {
            return false
        } else if (WooPermissionUtils.hasStoragePermission(activity!!)) {
            return true
        }

        val permissions = arrayOf(permission.WRITE_EXTERNAL_STORAGE)
        requestPermissions(permissions, WooPermissionUtils.STORAGE_PERMISSION_REQUEST_CODE)
        return false
    }

    /**
     * Requests camera & storage permissions, returns true only if permissions are already
     * available. Note that we need to ask for both permissions because we also need storage
     * permission to store media from the camera.
     */
    private fun requestCameraPermission(): Boolean {
        if (!isAdded) {
            return false
        }

        val hasStorage = WooPermissionUtils.hasStoragePermission(activity!!)
        val hasCamera = WooPermissionUtils.hasCameraPermission(activity!!)
        if (hasStorage && hasCamera) {
            return true
        }

        val permissions = when {
            hasStorage -> arrayOf(permission.CAMERA)
            hasCamera -> arrayOf(permission.WRITE_EXTERNAL_STORAGE)
            else -> arrayOf(permission.CAMERA, permission.WRITE_EXTERNAL_STORAGE)
        }

        requestPermissions(
                permissions,
                WooPermissionUtils.CAMERA_PERMISSION_REQUEST_CODE
        )
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (!isAdded) {
            return
        }

        val allGranted = WooPermissionUtils.setPermissionListAsked(
                activity!!, requestCode, permissions, grantResults, checkForAlwaysDenied = true
        )

        if (allGranted) {
            when (requestCode) {
                WooPermissionUtils.STORAGE_PERMISSION_REQUEST_CODE -> {
                    chooseProductImage()
                }
                WooPermissionUtils.CAMERA_PERMISSION_REQUEST_CODE -> {
                    captureProduceImage()
                }
            }
        }
    }
}
