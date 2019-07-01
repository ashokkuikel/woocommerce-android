package com.woocommerce.android.ui.orders

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.navArgs
import com.woocommerce.android.AppPrefs
import com.woocommerce.android.R
import com.woocommerce.android.analytics.AnalyticsTracker
import com.woocommerce.android.analytics.AnalyticsTracker.Stat.ORDER_SHIPMENT_TRACKING_ADD_BUTTON_TAPPED
import com.woocommerce.android.tools.NetworkStatus
import com.woocommerce.android.ui.base.UIMessageResolver
import com.woocommerce.android.ui.main.MainActivity.Companion.BackPressListener
import com.woocommerce.android.util.DateUtils
import com.woocommerce.android.widgets.AppRatingDialog
import dagger.android.support.AndroidSupportInjection
import kotlinx.android.synthetic.main.fragment_add_shipment_tracking.*
import org.wordpress.android.fluxc.model.WCOrderShipmentTrackingModel
import org.wordpress.android.fluxc.model.order.OrderIdentifier
import java.util.Calendar
import javax.inject.Inject
import org.wordpress.android.fluxc.utils.DateUtils as FluxCDateUtils

class AddOrderShipmentTrackingFragment : androidx.fragment.app.Fragment(), AddOrderShipmentTrackingContract.View,
        AddOrderTrackingProviderActionListener, BackPressListener {
    companion object {
        const val FIELD_ORDER_TRACKING_NUMBER = "order-tracking-number"
        const val FIELD_ORDER_TRACKING_DATE_SHIPPED = "order-tracking-date-shipped"
        const val FIELD_ORDER_TRACKING_PROVIDER = "order-tracking-provider"
        const val FIELD_IS_CONFIRMING_DISCARD = "is-confirming-discard"
        const val FIELD_IS_CUSTOM_PROVIDER = "is-custom-provider"
        const val FIELD_ORDER_TRACKING_CUSTOM_PROVIDER_NAME = "order-tracking-custom-provider-name"
        const val FIELD_ORDER_TRACKING_CUSTOM_PROVIDER_URL = "order-tracking-custom-provider-url"
    }

    @Inject lateinit var networkStatus: NetworkStatus
    @Inject lateinit var uiMessageResolver: UIMessageResolver
    @Inject lateinit var presenter: AddOrderShipmentTrackingContract.Presenter

    private var isConfirmingDiscard = false
    private var shouldShowDiscardDialog = true
    private var isSelectedProviderCustom = false
    private lateinit var orderId: OrderIdentifier
    private var dateShippedPickerDialog: DatePickerDialog? = null
    private var providerListPickerDialog: AddOrderTrackingProviderListFragment? = null

    private val navArgs: AddOrderShipmentTrackingFragmentArgs by navArgs()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        retainInstance = true
    }

    override fun onAttach(context: Context?) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_add_shipment_tracking, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        activity?.title = getString(R.string.order_shipment_tracking_toolbar_title)

        orderId = navArgs.orderId

        if (savedInstanceState != null) {
            isSelectedProviderCustom = savedInstanceState.getBoolean(FIELD_IS_CUSTOM_PROVIDER, false)
            addTracking_number.setText(savedInstanceState.getString(FIELD_ORDER_TRACKING_NUMBER, ""))
            addTracking_date.text = savedInstanceState.getString(FIELD_ORDER_TRACKING_DATE_SHIPPED)
            addTracking_custom_provider_name.setText(
                    savedInstanceState.getString(FIELD_ORDER_TRACKING_CUSTOM_PROVIDER_NAME, "")
            )
            addTracking_custom_provider_url.setText(
                    savedInstanceState.getString(FIELD_ORDER_TRACKING_CUSTOM_PROVIDER_URL, "")
            )
            if (savedInstanceState.getBoolean(FIELD_IS_CONFIRMING_DISCARD)) {
                confirmDiscard()
            }
        } else {
            isSelectedProviderCustom = navArgs.isCustomProvider
            val dateShipped = FluxCDateUtils.getCurrentDateString()
            displayFormatDateShippedText(dateShipped)
        }

        val selectedCarrierName = savedInstanceState?.let {
            savedInstanceState.getString(FIELD_ORDER_TRACKING_PROVIDER)
        } ?: navArgs.orderTrackingProvider
        if (isCustomProvider()) {
            addTracking_custom_provider_name.setText(selectedCarrierName)
            addTracking_editCarrier.text = getString(R.string.order_shipment_tracking_custom_provider_section_name)
            showCustomProviderFields()
        } else {
            addTracking_editCarrier.text = selectedCarrierName
            hideCustomProviderFields()
        }

        presenter.takeView(this)

        // When date field is clicked, open calendar dialog with default date set to
        // current date if no date was previously selected
        addTracking_date.setOnClickListener {
            val calendar = FluxCDateUtils.getCalendarInstance(getDateShippedText())
            dateShippedPickerDialog = DatePickerDialog(requireActivity(),
                    DatePickerDialog.OnDateSetListener { _, year, month, dayOfMonth ->
                        val actualMonth = month + 1
                        displayFormatDateShippedText(String.format("$year-$actualMonth-$dayOfMonth"))
                    }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)
            )
            dateShippedPickerDialog?.show()
        }

        // When carrier field is clicked, open dialog fragment to display list of providers
        addTracking_editCarrier.setOnClickListener {
            providerListPickerDialog = AddOrderTrackingProviderListFragment
                    .newInstance(
                            listener = this,
                            selectedProviderText = addTracking_editCarrier.text.toString(),
                            orderIdentifier = orderId)
                    .also { it.show(fragmentManager, AddOrderTrackingProviderListFragment.TAG) }
        }
    }

    override fun onPause() {
        super.onPause()
        // If calendar dialog or provider list dialog is displaying when activity is in a paused state,
        // then dismiss the dialog
        dateShippedPickerDialog?.dismiss()
        dateShippedPickerDialog = null

        providerListPickerDialog?.dismiss()
        providerListPickerDialog = null
    }

    override fun onResume() {
        super.onResume()
        AnalyticsTracker.trackViewShown(this)
    }

    override fun onDestroy() {
        presenter.dropView()
        super.onDestroy()
    }

    /**
     * Reusing the same menu used for adding order notes
     */
    override fun onCreateOptionsMenu(menu: Menu?, inflater: MenuInflater?) {
        menu?.clear()
        inflater?.inflate(R.menu.menu_add, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        return when (item?.itemId) {
            R.id.menu_add -> {
                if (addTracking_editCarrier.text.toString().isEmpty()) {
                    addTracking_editCarrier.isFocusableInTouchMode = true
                    addTracking_editCarrier.requestFocus()
                    addTracking_editCarrier.error = getString(R.string.order_shipment_tracking_empty_provider)
                    addTracking_number.error = null
                    addTracking_custom_provider_name.error = null
                    return true
                }

                if (isCustomProvider() && addTracking_custom_provider_name.text.toString().isEmpty()) {
                    addTracking_number.error = null
                    addTracking_editCarrier.error = null
                    addTracking_custom_provider_name.requestFocus()
                    addTracking_custom_provider_name.error = getString(
                            R.string.order_shipment_tracking_empty_custom_provider_name
                    )
                    return true
                }

                if (addTracking_number.text.isNullOrEmpty()) {
                    addTracking_editCarrier.error = null
                    addTracking_custom_provider_name.error = null
                    addTracking_number.requestFocus()
                    addTracking_number.error = getString(R.string.order_shipment_tracking_empty_tracking_num)
                    return true
                }

                AnalyticsTracker.track(ORDER_SHIPMENT_TRACKING_ADD_BUTTON_TAPPED)
                AppRatingDialog.incrementInteractions()

                val providerText = getProviderText()
                val trackingNumText = addTracking_number.text.toString()
                val isCustomProvider = isCustomProvider()
                val customProviderTrackingUrl = addTracking_custom_provider_url.text.toString()

                AppPrefs.setSelectedShipmentTrackingProviderName(providerText)
                AppPrefs.setIsSelectedShipmentTrackingProviderNameCustom(isCustomProvider)

                val orderShipmentTrackingModel = WCOrderShipmentTrackingModel()
                orderShipmentTrackingModel.trackingNumber = trackingNumText
                orderShipmentTrackingModel.dateShipped = getDateShippedText()
                orderShipmentTrackingModel.trackingProvider = providerText
                if (isCustomProvider) {
                    orderShipmentTrackingModel.trackingLink = customProviderTrackingUrl
                }

                if (presenter.pushShipmentTrackingRecord(orderId, orderShipmentTrackingModel, isCustomProvider)) {
                    shouldShowDiscardDialog = false
                    activity?.onBackPressed()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Prevent back press in the main activity if the user entered a note so we can confirm the discard
     */
    override fun onRequestAllowBackPress(): Boolean {
        val providerText = addTracking_editCarrier.text.toString()
        val trackingNumText = addTracking_number.text.toString()
        val customProviderText = addTracking_custom_provider_name.text.toString()
        val customTrackingUrlText = addTracking_custom_provider_url.text.toString()
        val displayConfirmDialog = providerText.isNotEmpty() || trackingNumText.isNotEmpty() ||
                (isCustomProvider() && (customProviderText.isNotEmpty() || customTrackingUrlText.isNotEmpty()))

        return if (displayConfirmDialog && shouldShowDiscardDialog) {
            confirmDiscard()
            false
        } else {
            true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(FIELD_IS_CONFIRMING_DISCARD, isConfirmingDiscard)
        outState.putString(FIELD_ORDER_TRACKING_NUMBER, addTracking_number.text.toString())
        outState.putString(FIELD_ORDER_TRACKING_DATE_SHIPPED, addTracking_date.text.toString())
        outState.putString(FIELD_ORDER_TRACKING_PROVIDER, addTracking_editCarrier.text.toString())
        outState.putBoolean(FIELD_IS_CUSTOM_PROVIDER, isCustomProvider())
        outState.putString(FIELD_ORDER_TRACKING_CUSTOM_PROVIDER_NAME, addTracking_custom_provider_name.text.toString())
        outState.putString(FIELD_ORDER_TRACKING_CUSTOM_PROVIDER_URL, addTracking_custom_provider_url.text.toString())
    }

    override fun confirmDiscard() {
        isConfirmingDiscard = true
        AlertDialog.Builder(activity)
                .setMessage(R.string.order_shipment_tracking_confirm_discard)
                .setCancelable(true)
                .setPositiveButton(R.string.discard) { _, _ ->
                    shouldShowDiscardDialog = false
                    activity?.onBackPressed()
                }
                .setNegativeButton(R.string.keep_editing) { _, _ ->
                    isConfirmingDiscard = false
                }
                .show()
    }

    override fun getProviderText(): String {
        return if (isCustomProvider()) {
            addTracking_custom_provider_name.text.toString()
        } else {
            addTracking_editCarrier.text.toString().trim()
        }
    }

    /**
     * Formats the localized date string and returns it in the format of yyyy-MM-dd
     * for use with the API.
     *
     * example: May 9, 2019 -> 2019-05-09
     */
    override fun getDateShippedText(): String {
        val dateSelected = DateUtils.getDateFromLocalizedLongDateString(
                requireActivity(),
                addTracking_date.text.toString())
        return DateUtils.getYearMonthDayStringFromDate(dateSelected)
    }

    override fun onTrackingProviderSelected(selectedCarrierName: String) {
        addTracking_editCarrier.text = selectedCarrierName
        addTracking_editCarrier.error = null
        addTracking_editCarrier.isFocusableInTouchMode = false
        addTracking_editCarrier.isFocusable = false
        isSelectedProviderCustom = addTracking_editCarrier.text ==
                getString(R.string.order_shipment_tracking_custom_provider_section_name)
        // Display custom provider fields only if selectedCarrierName = custom provider
        if (isCustomProvider()) {
            showCustomProviderFields()
        } else {
            hideCustomProviderFields()
        }
    }

    override fun isCustomProvider() = isSelectedProviderCustom

    override fun showOfflineSnack() {
        uiMessageResolver.showOfflineSnack()
    }

    override fun showAddAddShipmentTrackingErrorSnack() {
        uiMessageResolver.getSnack(R.string.order_shipment_tracking_error).show()
    }

    override fun showAddShipmentTrackingSnack() {
        uiMessageResolver.getSnack(R.string.order_shipment_tracking_added).show()
    }

    private fun displayFormatDateShippedText(dateString: String) {
        context?.let {
            addTracking_date.text = DateUtils.getLocalizedLongDateString(
                    it,
                    dateString
            )
        }
    }

    private fun showCustomProviderFields() {
        addTracking_custom_provider_name_view.visibility = View.VISIBLE
        addTracking_custom_provider_url_view.visibility = View.VISIBLE
    }

    private fun hideCustomProviderFields() {
        addTracking_custom_provider_name_view.visibility = View.GONE
        addTracking_custom_provider_url_view.visibility = View.GONE
    }
}
