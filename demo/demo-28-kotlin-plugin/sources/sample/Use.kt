package sample

import kotlinx.serialization.KSerializer

object Use {
    val pointSerializer: KSerializer<Point> = Point.serializer()
}
