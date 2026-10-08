package app.pikminbloom.gps.data

import app.pikminbloom.gps.i18n.Lang
import app.pikminbloom.gps.i18n.Tr

/**
 * One OpenStreetMap tag condition, expressible BOTH as an Overpass QL filter and as a local
 * predicate over an element's tags.
 *
 * Both forms are generated from the same declaration on purpose: purity (「純點」) has to be judged
 * locally over everything the query returned, so a second, hand-written matcher would drift from
 * the query and quietly mis-classify places.
 */
data class TagRule(
    val key: String,
    /** Exact accepted values. Empty means "any value". */
    val values: List<String> = emptyList(),
    /** Substring/regex the value must contain. Used for free-form keys like `cuisine`. */
    val valueContains: String? = null,
    /** Only match elements that also carry a name (drops unnamed noise like every field path). */
    val requireName: Boolean = false,
) {
    fun toOverpass(): String = buildString {
        when {
            values.isNotEmpty() -> append("""["$key"~"^(${values.joinToString("|")})${'$'}"]""")
            valueContains != null -> append("""["$key"~"$valueContains"]""")
            else -> append("""["$key"]""")
        }
        if (requireName) append("""["name"]""")
    }

    fun matches(tags: Map<String, String>): Boolean {
        if (requireName && tags["name"].isNullOrBlank()) return false
        val v = tags[key] ?: return false
        return when {
            values.isNotEmpty() -> v in values
            valueContains != null -> Regex(valueContains, RegexOption.IGNORE_CASE).containsMatchIn(v)
            else -> true
        }
    }
}

/**
 * Pikmin Bloom decor (飾品) and the kind of real-world place that produces it.
 *
 * In Pikmin Bloom the decor a Pikmin brings back is decided by the **category** of the place you
 * walk in, not by a specific unique location, so the category can be looked up anywhere in the
 * world from OpenStreetMap rather than from a per-country location list.
 *
 * A "pure point" (純點) has no *other* decor-producing category within [PURITY_RADIUS_M]: walk
 * there and every Pikmin comes back with the decor you wanted instead of a mix.
 *
 * The decor-to-place mapping follows the community reference table at treelazy.com/pikmin/decor;
 * the OpenStreetMap tags are ours. The English and Japanese names describe the decor; they are not checked against
 * the game's official names in those languages.
 */
enum class Decor(
    private val decor3: Tr,
    private val place3: Tr,
    val rules: List<TagRule>,
) {
    LURE(Tr("擬餌", "Fishing lure", "釣りのルアー"), Tr("水邊", "Waterside", "水辺"), listOf(
        TagRule("natural", values = listOf("water", "coastline")),
        TagRule("waterway", values = listOf("river", "stream", "canal")),
    )),
    STICKER(Tr("貼紙", "Sticker", "シール"), Tr("路邊", "Roadside", "道ばた"), listOf(
        TagRule("highway", values = listOf("pedestrian", "footway", "living_street"), requireName = true),
    )),
    STAG_BEETLE(Tr("鍬形蟲", "Stag beetle", "クワガタ"), Tr("森林", "Forest", "森"), listOf(
        TagRule("natural", values = listOf("wood")),
        TagRule("landuse", values = listOf("forest")),
    )),
    BUS_MODEL(Tr("公車紙模型", "Paper bus", "バスのペーパークラフト"), Tr("公車站", "Bus stop", "バス停"), listOf(
        TagRule("highway", values = listOf("bus_stop")),
        TagRule("amenity", values = listOf("bus_station")),
    )),
    CLOVER(Tr("幸運草", "Clover", "クローバー"), Tr("公園", "Park", "公園"), listOf(TagRule("leisure", values = listOf("park")))),
    BOTTLE_CAP(Tr("瓶蓋", "Bottle cap", "ボトルキャップ"), Tr("便利商店", "Convenience store", "コンビニ"), listOf(TagRule("shop", values = listOf("convenience")))),
    FORTUNE(Tr("運勢", "Fortune", "おみくじ"), Tr("神社／廟宇", "Shrine / temple", "神社・お寺"), listOf(TagRule("amenity", values = listOf("place_of_worship")))),
    CHEF_HAT(Tr("廚師帽子", "Chef hat", "コック帽"), Tr("餐廳", "Restaurant", "レストラン"), listOf(TagRule("amenity", values = listOf("restaurant")))),
    BRIDGE_PIN(Tr("橋樑別針", "Bridge pin", "橋のピンバッジ"), Tr("橋梁", "Bridge", "橋"), listOf(
        TagRule("man_made", values = listOf("bridge")),
        TagRule("bridge", values = listOf("yes"), requireName = true),
    )),
    SHELL(Tr("貝殼", "Seashell", "貝がら"), Tr("海灘", "Beach", "浜辺"), listOf(TagRule("natural", values = listOf("beach")))),
    SCHOOL_BADGE(Tr("校徽胸針", "School badge", "校章バッジ"), Tr("大學與學院", "University / college", "大学"), listOf(TagRule("amenity", values = listOf("university", "college")))),
    MUSHROOM(Tr("蘑菇", "Mushroom", "キノコ"), Tr("超市", "Supermarket", "スーパー"), listOf(TagRule("shop", values = listOf("supermarket")))),
    RAMEN_KEYRING(Tr("拉麵鑰匙圈", "Ramen keychain", "ラーメンのキーホルダー"), Tr("拉麵店", "Ramen shop", "ラーメン屋"), listOf(TagRule("cuisine", valueContains = "ramen|noodle"))),
    HILL_BADGE(Tr("山丘別針徽章", "Hill badge", "山のバッジ"), Tr("山丘", "Hill", "山"), listOf(TagRule("natural", values = listOf("peak", "hill")))),
    COFFEE_CUP(Tr("咖啡杯", "Coffee cup", "コーヒーカップ"), Tr("咖啡廳", "Café", "カフェ"), listOf(TagRule("amenity", values = listOf("cafe")))),
    HOTEL_AMENITY(Tr("飯店備品", "Hotel amenities", "ホテルのアメニティ"), Tr("飯店", "Hotel", "ホテル"), listOf(TagRule("tourism", values = listOf("hotel", "motel", "hostel")))),
    HAIR_TIE(Tr("髮圈", "Hair tie", "ヘアゴム"), Tr("服裝店", "Clothing store", "洋服屋"), listOf(TagRule("shop", values = listOf("clothes", "boutique", "fashion")))),
    TOY_PLANE(Tr("飛機玩具", "Toy plane", "飛行機のおもちゃ"), Tr("機場", "Airport", "空港"), listOf(TagRule("aeroway", values = listOf("aerodrome")))),
    MINI_BOOK(Tr("迷你書", "Mini book", "豆本"), Tr("圖書館", "Library", "図書館"), listOf(TagRule("amenity", values = listOf("library")))),
    TOOTHBRUSH(Tr("牙刷", "Toothbrush", "歯ブラシ"), Tr("藥局", "Pharmacy", "薬局"), listOf(
        TagRule("amenity", values = listOf("pharmacy")),
        TagRule("shop", values = listOf("chemist")),
    )),
    TRAIN_MODEL(Tr("電車紙模型", "Paper train", "電車のペーパークラフト"), Tr("車站", "Station", "駅"), listOf(
        TagRule("railway", values = listOf("station")),
        TagRule("public_transport", values = listOf("station")),
    )),
    GYM_KEYRING(Tr("體育館鑰匙圈", "Stadium keychain", "スタジアムのキーホルダー"), Tr("體育館", "Stadium / gym", "スタジアム"), listOf(TagRule("leisure", values = listOf("sports_centre", "stadium")))),
    STAMP(Tr("郵票", "Stamp", "切手"), Tr("郵局", "Post office", "郵便局"), listOf(TagRule("amenity", values = listOf("post_office")))),
    BAGUETTE(Tr("法國麵包", "Baguette", "バゲット"), Tr("麵包店", "Bakery", "パン屋"), listOf(TagRule("shop", values = listOf("bakery")))),
    STATIONERY(Tr("文具", "Stationery", "文房具"), Tr("文具店", "Stationery store", "文房具屋"), listOf(TagRule("shop", values = listOf("stationery")))),
    PIZZA(Tr("披薩", "Pizza", "ピザ"), Tr("義式餐廳", "Italian restaurant", "イタリアン"), listOf(TagRule("cuisine", valueContains = "pizza|italian"))),
    TOOLS(Tr("工具", "Tools", "工具"), Tr("五金行", "Hardware store", "ホームセンター"), listOf(TagRule("shop", values = listOf("hardware", "doityourself", "trade")))),
    SCISSORS(Tr("剪刀", "Scissors", "ハサミ"), Tr("美容院", "Hair salon", "美容院"), listOf(TagRule("shop", values = listOf("hairdresser", "beauty")))),
    BATTERY(Tr("電池", "Battery", "電池"), Tr("電器行", "Electronics store", "家電量販店"), listOf(TagRule("shop", values = listOf("electronics", "electrical")))),
    LAUNDRY(Tr("洗衣用品", "Laundry supplies", "洗濯用品"), Tr("自助洗衣店與乾洗店", "Laundromat / dry cleaner", "コインランドリー・クリーニング"), listOf(TagRule("shop", values = listOf("laundry", "dry_cleaning")))),
    MACARON(Tr("馬卡龍", "Macaron", "マカロン"), Tr("甜點店", "Sweet shop", "スイーツ店"), listOf(TagRule("shop", values = listOf("confectionery", "pastry")))),
    PARK_TICKET(Tr("主題樂園門票", "Theme park ticket", "テーマパークのチケット"), Tr("主題樂園", "Theme park", "テーマパーク"), listOf(TagRule("tourism", values = listOf("theme_park")))),
    BURGER(Tr("漢堡", "Burger", "ハンバーガー"), Tr("漢堡店", "Burger place", "ハンバーガー屋"), listOf(TagRule("cuisine", valueContains = "burger"))),
    SUSHI(Tr("壽司", "Sushi", "おすし"), Tr("壽司餐廳", "Sushi restaurant", "おすし屋"), listOf(TagRule("cuisine", valueContains = "sushi"))),
    PICTURE_FRAME(Tr("畫框", "Picture frame", "額縁"), Tr("美術館", "Art gallery / museum", "美術館"), listOf(TagRule("tourism", values = listOf("gallery", "museum")))),
    CURRY(Tr("一碗咖哩", "Curry bowl", "カレー"), Tr("咖哩餐廳", "Curry restaurant", "カレー屋"), listOf(TagRule("cuisine", valueContains = "curry|indian"))),
    KIMCHI(Tr("韓國泡菜", "Kimchi", "キムチ"), Tr("韓國餐廳", "Korean restaurant", "韓国料理店"), listOf(TagRule("cuisine", valueContains = "korean"))),
    DANDELION(Tr("蒲公英", "Dandelion", "タンポポ"), Tr("動物園", "Zoo", "動物園"), listOf(TagRule("tourism", values = listOf("zoo")))),
    COSMETICS(Tr("化妝品", "Cosmetics", "コスメ"), Tr("化妝品商店", "Cosmetics store", "コスメショップ"), listOf(TagRule("shop", values = listOf("cosmetics", "perfumery")))),
    POPCORN(Tr("爆米花", "Popcorn", "ポップコーン"), Tr("電影院", "Movie theater", "映画館"), listOf(TagRule("amenity", values = listOf("cinema")))),
    TACO(Tr("塔可餅", "Taco", "タコス"), Tr("墨西哥餐廳", "Mexican restaurant", "メキシコ料理店"), listOf(TagRule("cuisine", valueContains = "mexican|taco"))),
    ;

    /** The decor and its kind of place in the app's language (i18n.Lang): "擬餌", "Fishing lure", "釣りのルアー". */
    val decorName: String get() = decor3.text
    val placeName: String get() = place3.text

    fun nameIn(lang: Lang): String = decor3.of(lang)

    /** "擬餌（水邊）" / "Fishing lure (Waterside)", for pickers. */
    val label: String get() = if (Lang.current == Lang.EN) "$decorName ($placeName)" else "$decorName（$placeName）"

    /** True when [query] is part of the decor's or the place's name in any of the three languages. */
    fun nameContains(query: String): Boolean = decor3.anyContains(query) || place3.anyContains(query)

    fun matches(tags: Map<String, String>): Boolean = rules.any { it.matches(tags) }

    companion object {
        /** Pikmin Bloom mixes decor over roughly this radius; a pure point has nothing else inside it. */
        const val PURITY_RADIUS_M = 120.0

        fun byName(name: String): Decor? =
            entries.firstOrNull { it.name == name || name in listOf(it.decor3.zh, it.decor3.en, it.decor3.ja) }

        /** Which decor a place produces. A place can sit in more than one category. */
        fun classify(tags: Map<String, String>): List<Decor> = entries.filter { it.matches(tags) }
    }
}
