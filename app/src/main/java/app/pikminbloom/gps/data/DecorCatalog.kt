package app.pikminbloom.gps.data

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
 * the OpenStreetMap tags are ours.
 */
enum class Decor(
    val decorName: String,
    val placeName: String,
    val rules: List<TagRule>,
) {
    LURE("擬餌", "水邊", listOf(
        TagRule("natural", values = listOf("water", "coastline")),
        TagRule("waterway", values = listOf("river", "stream", "canal")),
    )),
    STICKER("貼紙", "路邊", listOf(
        TagRule("highway", values = listOf("pedestrian", "footway", "living_street"), requireName = true),
    )),
    STAG_BEETLE("鍬形蟲", "森林", listOf(
        TagRule("natural", values = listOf("wood")),
        TagRule("landuse", values = listOf("forest")),
    )),
    BUS_MODEL("公車紙模型", "公車站", listOf(
        TagRule("highway", values = listOf("bus_stop")),
        TagRule("amenity", values = listOf("bus_station")),
    )),
    CLOVER("幸運草", "公園", listOf(TagRule("leisure", values = listOf("park")))),
    BOTTLE_CAP("瓶蓋", "便利商店", listOf(TagRule("shop", values = listOf("convenience")))),
    FORTUNE("運勢", "神社／廟宇", listOf(TagRule("amenity", values = listOf("place_of_worship")))),
    CHEF_HAT("廚師帽子", "餐廳", listOf(TagRule("amenity", values = listOf("restaurant")))),
    BRIDGE_PIN("橋樑別針", "橋梁", listOf(
        TagRule("man_made", values = listOf("bridge")),
        TagRule("bridge", values = listOf("yes"), requireName = true),
    )),
    SHELL("貝殼", "海灘", listOf(TagRule("natural", values = listOf("beach")))),
    SCHOOL_BADGE("校徽胸針", "大學與學院", listOf(TagRule("amenity", values = listOf("university", "college")))),
    MUSHROOM("蘑菇", "超市", listOf(TagRule("shop", values = listOf("supermarket")))),
    RAMEN_KEYRING("拉麵鑰匙圈", "拉麵店", listOf(TagRule("cuisine", valueContains = "ramen|noodle"))),
    HILL_BADGE("山丘別針徽章", "山丘", listOf(TagRule("natural", values = listOf("peak", "hill")))),
    COFFEE_CUP("咖啡杯", "咖啡廳", listOf(TagRule("amenity", values = listOf("cafe")))),
    HOTEL_AMENITY("飯店備品", "飯店", listOf(TagRule("tourism", values = listOf("hotel", "motel", "hostel")))),
    HAIR_TIE("髮圈", "服裝店", listOf(TagRule("shop", values = listOf("clothes", "boutique", "fashion")))),
    TOY_PLANE("飛機玩具", "機場", listOf(TagRule("aeroway", values = listOf("aerodrome")))),
    MINI_BOOK("迷你書", "圖書館", listOf(TagRule("amenity", values = listOf("library")))),
    TOOTHBRUSH("牙刷", "藥局", listOf(
        TagRule("amenity", values = listOf("pharmacy")),
        TagRule("shop", values = listOf("chemist")),
    )),
    TRAIN_MODEL("電車紙模型", "車站", listOf(
        TagRule("railway", values = listOf("station")),
        TagRule("public_transport", values = listOf("station")),
    )),
    GYM_KEYRING("體育館鑰匙圈", "體育館", listOf(TagRule("leisure", values = listOf("sports_centre", "stadium")))),
    STAMP("郵票", "郵局", listOf(TagRule("amenity", values = listOf("post_office")))),
    BAGUETTE("法國麵包", "麵包店", listOf(TagRule("shop", values = listOf("bakery")))),
    STATIONERY("文具", "文具店", listOf(TagRule("shop", values = listOf("stationery")))),
    PIZZA("披薩", "義式餐廳", listOf(TagRule("cuisine", valueContains = "pizza|italian"))),
    TOOLS("工具", "五金行", listOf(TagRule("shop", values = listOf("hardware", "doityourself", "trade")))),
    SCISSORS("剪刀", "美容院", listOf(TagRule("shop", values = listOf("hairdresser", "beauty")))),
    BATTERY("電池", "電器行", listOf(TagRule("shop", values = listOf("electronics", "electrical")))),
    LAUNDRY("洗衣用品", "自助洗衣店與乾洗店", listOf(TagRule("shop", values = listOf("laundry", "dry_cleaning")))),
    MACARON("馬卡龍", "甜點店", listOf(TagRule("shop", values = listOf("confectionery", "pastry")))),
    PARK_TICKET("主題樂園門票", "主題樂園", listOf(TagRule("tourism", values = listOf("theme_park")))),
    BURGER("漢堡", "漢堡店", listOf(TagRule("cuisine", valueContains = "burger"))),
    SUSHI("壽司", "壽司餐廳", listOf(TagRule("cuisine", valueContains = "sushi"))),
    PICTURE_FRAME("畫框", "美術館", listOf(TagRule("tourism", values = listOf("gallery", "museum")))),
    CURRY("一碗咖哩", "咖哩餐廳", listOf(TagRule("cuisine", valueContains = "curry|indian"))),
    KIMCHI("韓國泡菜", "韓國餐廳", listOf(TagRule("cuisine", valueContains = "korean"))),
    DANDELION("蒲公英", "動物園", listOf(TagRule("tourism", values = listOf("zoo")))),
    COSMETICS("化妝品", "化妝品商店", listOf(TagRule("shop", values = listOf("cosmetics", "perfumery")))),
    POPCORN("爆米花", "電影院", listOf(TagRule("amenity", values = listOf("cinema")))),
    TACO("塔可餅", "墨西哥餐廳", listOf(TagRule("cuisine", valueContains = "mexican|taco"))),
    ;

    /** "擬餌（水邊）", for pickers. */
    val label: String get() = "$decorName（$placeName）"

    fun matches(tags: Map<String, String>): Boolean = rules.any { it.matches(tags) }

    companion object {
        /** Pikmin Bloom mixes decor over roughly this radius; a pure point has nothing else inside it. */
        const val PURITY_RADIUS_M = 120.0

        fun byName(name: String): Decor? = entries.firstOrNull { it.name == name || it.decorName == name }

        /** Which decor a place produces. A place can sit in more than one category. */
        fun classify(tags: Map<String, String>): List<Decor> = entries.filter { it.matches(tags) }
    }
}
