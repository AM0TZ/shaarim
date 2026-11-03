package com.amotz.rateswidget

enum class Cat(val he: String) { FIAT("מטבעות"), CRYPTO("קריפטו"), METAL("מתכות") }

/** glyph + color = the item's quick-recognition icon. color 0 = neutral circle (used behind flags). */
data class Item(val code: String, val cat: Cat, val he: String, val glyph: String, val color: Int)

private fun c(hex: String) = (0xFF000000 or hex.toLong(16)).toInt()

object Catalog {
    val items = listOf(
        Item("USD", Cat.FIAT, "דולר אמריקאי", "🇺🇸", 0),
        Item("EUR", Cat.FIAT, "אירו", "🇪🇺", 0),
        Item("GBP", Cat.FIAT, "לירה שטרלינג", "🇬🇧", 0),
        Item("CHF", Cat.FIAT, "פרנק שוויצרי", "🇨🇭", 0),
        Item("JPY", Cat.FIAT, "ין יפני", "🇯🇵", 0),
        Item("CAD", Cat.FIAT, "דולר קנדי", "🇨🇦", 0),
        Item("AUD", Cat.FIAT, "דולר אוסטרלי", "🇦🇺", 0),
        Item("CNY", Cat.FIAT, "יואן סיני", "🇨🇳", 0),
        Item("TRY", Cat.FIAT, "לירה טורקית", "🇹🇷", 0),
        Item("RUB", Cat.FIAT, "רובל רוסי", "🇷🇺", 0),

        Item("BTC", Cat.CRYPTO, "ביטקוין", "₿", c("F7931A")),
        Item("ETH", Cat.CRYPTO, "את׳ריום", "Ξ", c("627EEA")),
        Item("SOL", Cat.CRYPTO, "סולנה", "S", c("9945FF")),
        Item("XRP", Cat.CRYPTO, "ריפל", "X", c("346AA9")),
        Item("XLM", Cat.CRYPTO, "סטלר", "✦", c("08A5D6")),
        Item("QNT", Cat.CRYPTO, "קוואנט", "Q", c("5B6B7F")),
        Item("ADA", Cat.CRYPTO, "קרדנו", "₳", c("0033AD")),
        Item("DOGE", Cat.CRYPTO, "דוג׳קוין", "Ð", c("C2A633")),
        Item("LTC", Cat.CRYPTO, "לייטקוין", "Ł", c("345D9D")),
        Item("AVAX", Cat.CRYPTO, "אוולאנץ׳", "A", c("E84142")),
        Item("DOT", Cat.CRYPTO, "פולקדוט", "●", c("E6007A")),
        Item("LINK", Cat.CRYPTO, "צ׳יינלינק", "⬡", c("2A5ADA")),

        Item("XAU", Cat.METAL, "זהב", "Au", c("C9A227")),
        Item("XAG", Cat.METAL, "כסף", "Ag", c("7D8590")),
        Item("XPT", Cat.METAL, "פלטינה", "Pt", c("5E7388")),
        Item("XPD", Cat.METAL, "פלדיום", "Pd", c("8C6E5A")),
    )

    val defaults = setOf("USD", "EUR", "GBP", "BTC", "ETH", "SOL", "XAU", "XAG", "XPT")

    const val NEUTRAL = 0xFF2A3441.toInt()

    fun byCat(c: Cat) = items.filter { it.cat == c }
    fun get(code: String) = items.firstOrNull { it.code == code }
}
