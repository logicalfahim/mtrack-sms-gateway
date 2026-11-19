package com.mtrack.sms_gateway

import java.util.regex.Pattern

data class ParsedPayment(
    val isValid: Boolean,
    val platform: String,
    val type: String,
    val trxId: String,
    val senderNumber: String,
    val amount: String
)

object PaymentFilter {

    private val OUTGOING_KEYWORDS = arrayOf(
        "to bank", "to card", "cash out", "cash-out", "send money", "sent tk",
        "mobile recharge", "recharge", "pay bill", "payment to", "transferred to",
        "transfer to", "fee charged", "payment of tk"
    )

    private val SENDER_REGEX = Pattern.compile(
        "(?:from\\s+(?:a\\/c|account)?[\\s:]*|from\\s+agent[\\s:]*|customer[\\s:]*|sender[\\s:]*|payer[\\s:]*|by[\\s:]*|from[\\s:]+)([\\+0-9*xX\\-]{3,18})",
        Pattern.CASE_INSENSITIVE
    )
    private val SENDER_FALLBACK_REGEX = Pattern.compile(
        "(\\+?(?:88)?01[3-9][0-9*xX\\-]{5,12}|\\*{2,}[0-9*xX\\-]{2,10})",
        Pattern.CASE_INSENSITIVE
    )
    private val TRX_REGEX = Pattern.compile("(?:TrxID|TxnID|Txn\\s*Id|Trx\\s*Id)[\\s:]*([A-Za-z0-9]+)", Pattern.CASE_INSENSITIVE)
    private val AMOUNT_REGEX = Pattern.compile("(?:Tk|BDT)\\.?\\s*([\\d,]+\\.?\\d*)", Pattern.CASE_INSENSITIVE)

    fun isIncomingPayment(sender: String, body: String): Boolean {
        val parsed = parse(sender, body)
        return parsed.isValid
    }

    fun parse(sender: String, body: String): ParsedPayment {
        val s = sender.trim().lowercase()
        val b = body.trim()
        val bLower = b.lowercase()

        // 1. Strict Sender Verification:
        // bKash: "bkash" or "16247"
        // Nagad: "16167" or "nagad"
        // Rocket: STRICTLY "16216" ONLY!
        val isBkash = s.contains("bkash") || s == "16247"
        val isNagad = s.contains("nagad") || s == "16167"
        val isRocket = s == "16216" // Strictly 16216 only!

        if (!isBkash && !isNagad && !isRocket) {
            return ParsedPayment(false, "", "", "", "", "")
        }

        val platform = when {
            isBkash -> "bKash"
            isNagad -> "Nagad"
            else -> "Rocket"
        }

        // 2. Blacklist: Any Outgoing / Debit / Expense keyword instantly drops the message
        for (kw in OUTGOING_KEYWORDS) {
            if (bLower.contains(kw)) {
                // Safeguard: "payment of tk" can appear in incoming merchant notices: "received payment of Tk ... from ..."
                if (kw == "payment of tk" && (bLower.contains("received") || bLower.contains("from"))) {
                    continue
                }
                return ParsedPayment(false, platform, "Debit/Outgoing", "", "", "")
            }
        }

        // 3. Must have explicit Incoming Indicator
        val isCashIn = bLower.contains("cash in") || bLower.contains("cash-in")
        val isReceived = bLower.contains("received") || bLower.contains("receive") || bLower.contains("customer:")
        val isMerchantPayment = bLower.contains("payment") && (bLower.contains("received") || bLower.contains("from"))

        if (!isCashIn && !isReceived && !isMerchantPayment) {
            return ParsedPayment(false, platform, "", "", "", "")
        }

        // 4. Extract TrxID
        val trxMatcher = TRX_REGEX.matcher(b)
        if (!trxMatcher.find()) {
            return ParsedPayment(false, platform, "", "", "", "")
        }
        val trxId = trxMatcher.group(1)?.trim() ?: ""
        if (trxId.isEmpty()) {
            return ParsedPayment(false, platform, "", "", "", "")
        }

        // 5. Extract Amount
        val amtMatcher = AMOUNT_REGEX.matcher(b)
        val amount = if (amtMatcher.find()) {
            amtMatcher.group(1)?.replace(",", "")?.trim() ?: "0.00"
        } else {
            "0.00"
        }

        // 6. Extract Sender Mobile Number (As-Is: Full, Masked 0171***789, +88, Rocket 12-digit)
        var customerPhone = ""
        val senderMatcher = SENDER_REGEX.matcher(b)
        while (senderMatcher.find()) {
            val cand = senderMatcher.group(1)?.trim() ?: ""
            if (cand.isNotEmpty() && !cand.equals(trxId, ignoreCase = true)) {
                customerPhone = cand
                break
            }
        }
        if (customerPhone.isEmpty()) {
            // Fallback: search for any phone pattern in message that is not TrxID
            val fallbackMatcher = SENDER_FALLBACK_REGEX.matcher(b)
            while (fallbackMatcher.find()) {
                val cand = fallbackMatcher.group(1)?.trim() ?: ""
                if (cand.isNotEmpty() && !cand.equals(trxId, ignoreCase = true)) {
                    customerPhone = cand
                    break
                }
            }
        }

        // 7. Determine Category
        val type = when {
            isCashIn -> "Agent Cash In"
            isMerchantPayment -> "Merchant Payment"
            else -> "Customer Send Money"
        }

        return ParsedPayment(
            isValid = true,
            platform = platform,
            type = type,
            trxId = trxId,
            senderNumber = customerPhone,
            amount = amount
        )
    }
}
