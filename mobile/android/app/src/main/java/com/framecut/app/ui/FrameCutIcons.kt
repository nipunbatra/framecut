package com.framecut.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Two hand-built vectors instead of pulling in material-icons-extended, which
 * would add several megabytes for two glyphs.
 */
object FrameCutIcons {

    val Folder: ImageVector by lazy {
        ImageVector.Builder(
            name = "Folder",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(2f, 5f)
                lineTo(9.5f, 5f)
                lineTo(11.5f, 7.5f)
                lineTo(22f, 7.5f)
                lineTo(22f, 19f)
                lineTo(2f, 19f)
                close()
            }
        }.build()
    }

    val Movie: ImageVector by lazy {
        ImageVector.Builder(
            name = "Movie",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(3f, 5f)
                lineTo(21f, 5f)
                lineTo(21f, 19f)
                lineTo(3f, 19f)
                close()
                // Play triangle punched out of the frame.
                moveTo(10f, 8.5f)
                lineTo(16f, 12f)
                lineTo(10f, 15.5f)
                close()
            }
        }.build()
    }
}
