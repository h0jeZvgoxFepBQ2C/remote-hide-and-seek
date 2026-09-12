package at.flave.versteckspiel

data class Sound(val id: String, val emoji: String, val label: String, val res: Int, val color: Int)

data class Category(val title: String, val emoji: String, val sounds: List<Sound>)

/**
 * Die Reiter der Fernbedienung. Reihenfolge und Inhalt muessen auf allen Handys
 * gleich sein - verschickt wird nur der Index aus [SOUNDS].
 */
val CATEGORIES = listOf(
    Category("Tiere", "🐮", listOf(
        Sound("cat",     "🐱", "Miau",      R.raw.cat,     0xFFF4A62A.toInt()),
        Sound("dog",     "🐶", "Wuff",      R.raw.dog,     0xFF8D6E63.toInt()),
        Sound("cow",     "🐮", "Muuh",      R.raw.cow,     0xFFEC6A8C.toInt()),
        Sound("frog",    "🐸", "Quak",      R.raw.frog,    0xFF4CAF50.toInt()),
        Sound("duck",    "🦆", "Schnatter", R.raw.duck,    0xFF29B6D6.toInt()),
        Sound("rooster", "🐓", "Kikeriki",  R.raw.rooster, 0xFFE0533D.toInt()),
        Sound("bear",    "🐻", "Brumm",     R.raw.bear,    0xFF9C6B3F.toInt()),
        Sound("owl",     "🦉", "Huhu",      R.raw.owl,     0xFF5C6BC0.toInt()),
    )),
    Category("Lustig", "😂", listOf(
        Sound("fart1",  "💨", "Pups",        R.raw.fart1,  0xFF7CB342.toInt()),
        Sound("fart2",  "🎺", "Trompete",    R.raw.fart2,  0xFF9E9D24.toInt()),
        Sound("fart3",  "🫧", "Kurzer",      R.raw.fart3,  0xFF66BB6A.toInt()),
        Sound("fart4",  "🚂", "Dreifach",    R.raw.fart4,  0xFF558B2F.toInt()),
        Sound("burp1",  "😮", "Rülps",       R.raw.burp1,  0xFFEF6C00.toInt()),
        Sound("burp2",  "😝", "Großer Rülps", R.raw.burp2, 0xFFD84315.toInt()),
        Sound("laugh1", "😄", "Kichern",     R.raw.laugh1, 0xFFFBC02D.toInt()),
        Sound("laugh2", "😂", "Alle lachen", R.raw.laugh2, 0xFFF9A825.toInt()),
    )),
    Category("Menschen", "🧍", listOf(
        Sound("yodel1",  "🏔️", "Holadio",     R.raw.yodel1,  0xFF26A69A.toInt()),
        Sound("yodel2",  "🎵", "Jodler",      R.raw.yodel2,  0xFF00897B.toInt()),
        Sound("yodel3",  "🤠", "Juchzer",     R.raw.yodel3,  0xFF00838F.toInt()),
        Sound("yodel4",  "📻", "Alter Jodler", R.raw.yodel4, 0xFF00695C.toInt()),
        Sound("whistle", "😗", "Pfeifen",     R.raw.whistle, 0xFF42A5F5.toInt()),
        Sound("clap",    "👏", "Klatschen",   R.raw.clap,    0xFF7E57C2.toInt()),
        Sound("yippie",  "🎉", "Juhuu!",      R.raw.yippie,  0xFF8E24AA.toInt()),
    )),
)

/** Flache Liste - der Index darin ist die Kennung auf der Leitung. */
val SOUNDS: List<Sound> = CATEGORIES.flatMap { it.sounds }
