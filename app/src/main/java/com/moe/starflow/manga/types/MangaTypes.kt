package com.moe.starflow.manga.types
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import org.locationtech.jts.geom.Coordinate

// 共享类型别名：兼容旧 clipper API
typealias Point64 = Coordinate
typealias Path64 = MutableList<Coordinate>
typealias Paths64 = MutableList<MutableList<Coordinate>>