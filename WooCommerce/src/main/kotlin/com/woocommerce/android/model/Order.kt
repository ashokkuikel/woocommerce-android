package com.woocommerce.android.model

import android.os.Parcelable
import com.woocommerce.android.extensions.roundError
import com.woocommerce.android.model.Order.Address
import com.woocommerce.android.model.Order.Address.Type.BILLING
import com.woocommerce.android.model.Order.Address.Type.SHIPPING
import com.woocommerce.android.model.Order.Item
import com.woocommerce.android.ui.products.ProductHelper
import kotlinx.android.parcel.IgnoredOnParcel
import kotlinx.android.parcel.Parcelize
import org.wordpress.android.fluxc.model.WCOrderModel
import org.wordpress.android.fluxc.model.order.OrderIdentifier
import org.wordpress.android.fluxc.network.rest.wpcom.wc.order.CoreOrderStatus
import org.wordpress.android.fluxc.network.rest.wpcom.wc.order.CoreOrderStatus.PENDING
import org.wordpress.android.util.DateTimeUtils
import java.math.BigDecimal
import java.util.Date

@Parcelize
data class Order(
    val identifier: OrderIdentifier,
    val remoteId: Long,
    val number: String,
    val localSiteId: Int,
    val dateCreated: Date,
    val dateModified: Date,
    val datePaid: Date?,
    val status: CoreOrderStatus,
    val total: BigDecimal,
    val productsTotal: BigDecimal,
    val totalTax: BigDecimal,
    val shippingTotal: BigDecimal,
    val discountTotal: BigDecimal,
    val refundTotal: BigDecimal,
    val currency: String,
    val customerNote: String,
    val discountCodes: String,
    val paymentMethod: String,
    val paymentMethodTitle: String,
    val pricesIncludeTax: Boolean,
    val billingAddress: Address,
    val shippingAddress: Address,
    val items: List<Item>
) : Parcelable {
    @Parcelize
    data class Item(
        val itemId: Long,
        val productId: Long,
        val name: String,
        val price: BigDecimal,
        val sku: String,
        val quantity: Int,
        val subtotal: BigDecimal,
        val totalTax: BigDecimal,
        val total: BigDecimal,
        val variationId: Long
    ) : Parcelable {
        @IgnoredOnParcel
        val uniqueId: Long = ProductHelper.productOrVariationId(productId, variationId)
    }

    @Parcelize
    data class Address(
        val address1: String,
        val address2: String,
        val city: String,
        val company: String,
        val country: String,
        val firstName: String,
        val lastName: String,
        val postcode: String,
        val state: String,
        val type: Type
    ) : Parcelable {
        enum class Type {
            BILLING,
            SHIPPING
        }
    }
}

fun WCOrderModel.toAppModel(): Order {
    return Order(
            OrderIdentifier(this),
            this.remoteOrderId,
            this.number,
            this.localSiteId,
            DateTimeUtils.dateUTCFromIso8601(this.dateCreated) ?: Date(),
            DateTimeUtils.dateUTCFromIso8601(this.dateModified) ?: Date(),
            DateTimeUtils.dateUTCFromIso8601(this.datePaid),
            CoreOrderStatus.fromValue(this.status) ?: PENDING,
            this.total.toBigDecimalOrNull()?.roundError() ?: BigDecimal.ZERO,
            this.getOrderSubtotal().toBigDecimal().roundError(),
            this.totalTax.toBigDecimalOrNull()?.roundError() ?: BigDecimal.ZERO,
            this.shippingTotal.toBigDecimalOrNull()?.roundError() ?: BigDecimal.ZERO,
            this.discountTotal.toBigDecimalOrNull()?.roundError() ?: BigDecimal.ZERO,
            -this.refundTotal.toBigDecimal().roundError(), // WCOrderModel.refundTotal is NEGATIVE
            this.currency,
            this.customerNote,
            this.discountCodes,
            this.paymentMethod,
            this.paymentMethodTitle,
            this.pricesIncludeTax,
            this.getBillingAddress().let {
                Address(
                        it.address1,
                        it.address2,
                        it.city,
                        it.company,
                        it.country,
                        it.firstName,
                        it.lastName,
                        it.postcode,
                        it.state,
                        BILLING
                )
            },
            this.getShippingAddress().let {
                Address(
                        it.address1,
                        it.address2,
                        it.city,
                        it.company,
                        it.country,
                        it.firstName,
                        it.lastName,
                        it.postcode,
                        it.state,
                        SHIPPING
                )
            },
            getLineItemList()
                    .filter { it.productId != null && it.id != null }
                    .map {
                        Item(
                                it.id!!,
                                it.productId!!,
                                it.name ?: "",
                                it.price?.toBigDecimalOrNull()?.roundError() ?: BigDecimal.ZERO,
                                it.sku ?: "",
                                it.quantity?.toInt() ?: 0,
                                it.subtotal?.toBigDecimalOrNull()?.roundError() ?: BigDecimal.ZERO,
                                it.totalTax?.toBigDecimalOrNull()?.roundError() ?: BigDecimal.ZERO,
                                it.total?.toBigDecimalOrNull()?.roundError() ?: BigDecimal.ZERO,
                                it.variationId ?: 0
                        )
                    }
    )
}
