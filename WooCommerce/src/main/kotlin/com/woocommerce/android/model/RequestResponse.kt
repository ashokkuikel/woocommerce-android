package com.woocommerce.android.model

sealed class RequestResponse {
    object Success : RequestResponse()
    object Error : RequestResponse()
    object Retry : RequestResponse()
    object NoActionNeeded : RequestResponse()
    object IncorrectProductSku : RequestResponse()
}
