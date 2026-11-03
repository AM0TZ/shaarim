package com.amotz.rateswidget

enum class Cat(val he: String) { FIAT("מטבעות"), CRYPTO("קריפטו"), METAL("מתכות") }

data class Item(val code: String, val cat: Cat, val he: String)

object Catalog {
    val items = listOf(
        Item("USD", Cat.FIAT, "דולר אמריקאי"),
        Item("EUR", Cat.FIAT, "אירו"),
        Item("GBP", Cat.FIAT, "לירה שטרלינג"),
        Item("CHF", Cat.FIAT, "פרנק שוויצרי"),
        Item("JPY", Cat.FIAT, "ין יפני"),
        Item("CAD", Cat.FIAT, "דולר קנדי"),
        Item("AUD", Cat.FIAT, "דולר אוסטרלי"),
        Item("CNY", Cat.FIAT, "יואן סיני"),
        Item("TRY", Cat.FIAT, "לירה טורקית"),
        Item("RUB", Cat.FIAT, "רובל רוסי"),

        Item("BTC", Cat.CRYPTO, "ביטקוין"),
        Item("ETH", Cat.CRYPTO, "את׳ריום"),
        Item("SOL", Cat.CRYPTO, "סולנה"),
        Item("XRP", Cat.CRYPTO, "ריפל"),
        Item("ADA", Cat.CRYPTO, "קרדנו"),
        Item("DOGE", Cat.CRYPTO, "דוג׳קוין"),
        Item("LTC", Cat.CRYPTO, "לייטקוין"),
        Item("AVAX", Cat.CRYPTO, "אוולאנץ׳"),
        Item("DOT", Cat.CRYPTO, "פולקדוט"),
        Item("LINK", Cat.CRYPTO, "צ׳יינלינק"),

        Item("XAU", Cat.METAL, "זהב"),
        Item("XAG", Cat.METAL, "כסף"),
        Item("XPT", Cat.METAL, "פלטינה"),
        Item("XPD", Cat.METAL, "פלדיום"),
    )

    val defaults = setOf("USD", "EUR", "GBP", "BTC", "ETH", "SOL", "XAU", "XAG", "XPT")

    fun byCat(c: Cat) = items.filter { it.cat == c }
    fun get(code: String) = items.firstOrNull { it.code == code }

    const val WIDGET_MAX = 7
}
