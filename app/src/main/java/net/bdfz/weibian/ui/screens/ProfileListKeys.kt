package net.bdfz.weibian.ui.screens

internal object ProfileListKeys {
    fun achievement(id: String): String = "achievement:$id"

    fun favorite(chapterId: Int): String = "favorite:$chapterId"

    fun note(chapterId: Int): String = "note:$chapterId"
}
