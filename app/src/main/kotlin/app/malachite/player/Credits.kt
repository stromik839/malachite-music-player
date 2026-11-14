package app.malachite.player

import app.malachite.core.i18n.tr

/**
 * Who made this player.
 *
 * If you change the code and publish your own version:
 *  - keep [CREATOR] and the creator's logo as they are (a condition of the licence, see NOTICE.md);
 *  - add yourself to [MODIFIED_BY] – the "About" screen then shows you under
 *    "Изменили код / модифицировали";
 *  - point [SOURCE_URL] / [ISSUES_URL] to your own repository, so that reports about your
 *    version come to you.
 */
object Credits {
    const val APP_NAME = "Malachite"

    /** The main creator: the idea, the design decisions and the direction of the whole project. */
    const val CREATOR = "Krysmed"
    const val CREATOR_DISPLAY = "𝗞𝗿𝘆𝘀𝗺𝗲𝗱 𝗱𝗲𝘃𝗲𝗹𝗼𝗽𝗲𝗿"
    const val CREATOR_LINK = "https://t.me/+Awp124F8mthjNDIy"
    val CREATOR_LINK_LABEL get() = tr("Telegram-канал")

    /** How the code was written. */
    val CODE_BY get() = tr("ИИ Claude (Anthropic)")

    const val SOURCE_URL = "https://github.com/stromik839/malachite"
    const val ISSUES_URL = SOURCE_URL + "/issues"

    /** A link for voluntary donations (a page such as Ko-fi or Liberapay); may stay empty. */
    const val DONATE_URL = ""

    /** Where a voluntary donation in cryptocurrency can be sent. */
    data class Wallet(val coin: String, val network: String, val address: String, val minimum: String)

    /**
     * The creator's addresses. Every one of them was checked against its built-in checksum
     * (Bitcoin bech32, TRON base58check, EIP-55, TON CRC16) before it was written here.
     * An address accepts ONLY its own coin in its own network – anything else is lost.
     * If you publish a modified version, do not put your own addresses in place of these:
     * add them separately and say clearly whose they are.
     */
    val DONATE_WALLETS: List<Wallet> = listOf(
        Wallet("Bitcoin (BTC)", "Bitcoin", "bc1q2p5lzd42lpxcwnlynt9dtwzd3ntw2aykn5y8ts", "0.00001 BTC"),
        Wallet("TRX", "TRON", "TYbzijonEYTXiWQG11Jqbs6XqGCmF6Zejn", "0.1 TRX"),
        Wallet("GRAM", "The Open Network (TON)", "UQDlK5Ei9JA4OrznOmwa6yFTP1NSfZGaYlecSftLVKkeDrQ3", "0.01 GRAM"),
        Wallet("USDT", "TRON (TRC20)", "TYbzijonEYTXiWQG11Jqbs6XqGCmF6Zejn", "0.1 USDT"),
        Wallet("USDT", "The Open Network (TON)", "UQDlK5Ei9JA4OrznOmwa6yFTP1NSfZGaYlecSftLVKkeDrQ3", "0.1 USDT"),
        Wallet("USDT", "BNB Smart Chain (BEP20)", "0x6E7393506C78e274e69e52d4DfFcCa9f53E6023E", "0.1 USDT"),
        Wallet("USDT", "Ethereum (ERC20)", "0x70A3D87837Ae24217e1ea91F95968Eac0B7F7232", "0.1 USDT"),
    )

    val LICENSE_NAME get() = tr("GNU GPL 3.0 или новее, с дополнительными условиями об авторстве")

    /** One person (or team) who changed the code of the original player. */
    data class Modder(val name: String, val what: String = "", val link: String = "")

    /**
     * People who modified the player. The original version has none.
     * Example: Modder("Имя", "добавил поддержку …", "https://example.org")
     */
    val MODIFIED_BY: List<Modder> = listOf()

    /** The creator's mark, a 100×100 square (the same as in the creator's app "Розклад"). */
    const val LOGO_PATH = "M41.9 80l2.7 1.6l5.3 2.8l3.1-1.5l5.1-2.9l-1.2-0.4l-3-1.4l-1.9-1.1l-2-1.3l-3.9 2.4l-3 1.4zM76 54l-1.2 0.3l-1.7 0.2l-3.8-0.1l-1.6-0.2l-2.3-0.6l-1.7-0.5l-2.2-1l0.4 1.2l0.2 1.8l0 2.4l-0.4 2.5l-0.7 2.4l-1 2.3l-1.1 1.9l-1.6 2.3l-2.3 2.7l1.1 0.7l2 1l3.3 1.1l3.7 0.6l0.8-0.5l2.8-2.6l2.1-2.4l2.7-3.7l1.2-2.1l0.5-1.4l0.5-2.1l0.2-2.1zM24 54l0.1 4.1l0.2 2.1l0.5 2.1l0.5 1.4l1.2 2.1l2.7 3.7l2.1 2.4l2.8 2.6l0.8 0.5l3.7-0.6l3.3-1.1l2-1l1.1-0.7l-2.3-2.7l-1.6-2.3l-1.1-1.9l-1-2.3l-0.7-2.4l-0.4-2.5l0-2.4l0.2-1.8l0.4-1.2l-2.2 1l-1.7 0.5l-2.3 0.6l-1.6 0.2l-3.8 0.1l-1.7-0.2zM50 45.9l-1.6 1.5l-1.1 1.2l-1.7 2.4l-0.6 1.4l-0.5 1.5l-0.3 1.4l-0.1 1.6l0.2 1.4l0.5 1.5l1.3 2.9l1.9 2.7l0.9 1.1l1.1 1.1l1.1-1.1l1.6-1.9l1.2-1.9l0.6-1.1l0.7-1.8l0.5-1.5l0.2-1.4l0-1.2l-0.3-1.4l-0.4-1.4l-1-2.2l-1-1.5l-1.1-1.3l-1.4-1.5zM79.3 38.4l-2.4-0.6l-2.9-0.3l-2.2 0l-3.2 0.3l-2.7 0.5l-3.6 0.9l-3.6 1.4l-2 0.9l1.9 1.5l2.2 1.6l2.3 1.3l1.8 0.8l2.1 0.8l2.1 0.5l2 0.3l2.3-0.1l1.8-0.5l1.5-0.7l0.5-2.2l1.4-4zM20.7 38.4l0.7 2.4l1.4 4l0.5 2.2l1.5 0.7l1.8 0.5l2.3 0.1l2-0.3l2.1-0.5l2.1-0.8l1.8-0.8l2.3-1.3l2.2-1.6l1.9-1.5l-2-0.9l-3.6-1.4l-3-0.8l-3.3-0.6l-3.2-0.3l-2.2 0l-2.9 0.3zM56.9 34.5l1.9-0.7l3.4-1.1l3.3-0.8l3.2-0.5l2.5-0.2l3.5 0l4.4 0.6l-1.3-2.2l-1.4-1.7l-1.9-2l-1.7-1.4l-1.9-1.2l-2-1.1l-2.5-1l-2.5-0.8l-3.6 7.9l-1.8 3.5zM43.1 34.5l-1.6-2.7l-1.8-3.4l-3.6-8l-2.5 0.8l-2.5 1l-2 1.1l-1.9 1.2l-1.7 1.4l-1.9 2l-1.4 1.7l-1.3 2.2l4.4-0.6l3.5 0l2.5 0.2l3.2 0.5l3.3 0.8l3.4 1.1zM42.5 19.3l1.7 4l2.4 4.8l2.1 3.5l1.3 2l2.4-3.7l1.5-2.8l2.3-4.7l1.3-3.1l-3.8-0.3l-7.4 0z"
}
