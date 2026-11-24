package de.yuuto.aimtune.app

import com.badlogic.gdx.graphics.Color

/** Colors of the trainer scene (room, targets, Range bot). The UI reads its colors from [de.yuuto.aimtune.app.ui.Tokens]. */
object Theme {
    val bg: Color = Color.valueOf("161826")
    val surface: Color = Color.valueOf("232532")
    val text: Color = Color.valueOf("e9e9ed")
    val accent: Color = Color.valueOf("9184d9")
    val accentSoft: Color = Color.valueOf("b5abfc")
    val roomWall: Color = Color.valueOf("292b31")
    val roomFloor: Color = Color.valueOf("454d66")
    val roomStructure: Color = Color.valueOf("4d556f")
    val roomPanel: Color = Color.valueOf("434a63")
    val roomPillar: Color = Color.valueOf("69729a")
    val roomCrate: Color = Color.valueOf("575f80")
    val roomGrid: Color = Color.valueOf("4a5270")
    val roomFog: Color = Color.valueOf("161826")
    val roomBody: Color = Color.valueOf("6d63b0")
    /** Range mode: the bot is sand and ochre so it stands out from the blue-grey room; the lane lines are a lit blue-grey. */
    val botHead: Color = Color.valueOf("e6d9b8")
    val botTorso: Color = Color.valueOf("c79a55")
    val botLimb: Color = Color.valueOf("8f7048")
    val laneLine: Color = Color.valueOf("8d97c4")
    val caution: Color = Color.valueOf("E69F00")
}
