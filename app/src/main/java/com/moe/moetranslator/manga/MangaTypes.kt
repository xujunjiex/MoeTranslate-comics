package com.moe.moetranslator.manga

import org.locationtech.jts.geom.Coordinate

// 共享类型别名：兼容旧 clipper API
typealias Point64 = Coordinate
typealias Path64 = MutableList<Coordinate>
typealias Paths64 = MutableList<MutableList<Coordinate>>