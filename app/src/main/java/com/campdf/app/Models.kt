package com.campdf.app

import java.io.File

enum class FilterMode { ORIGINAL, LIGHTEN, ENHANCE, NO_SHADOW, BW, MAGIC }

enum class Screen { Welcome, Home, Viewer, Camera, Crop, Review, SignPad, SignPlace, Export, Settings }

/**
 * One scanned page.
 * rawPath  = original capture/import (used for re-cropping)
 * basePath = cropped + perspective-corrected image (before rotation/filter)
 * corners  = normalized TL,TR,BR,BL (x,y) used for the crop, 8 floats
 */
data class PageItem(
    val id: Long,
    val rawPath: String,
    val basePath: String,
    val corners: List<Float>?,
    val rotation: Int = 0,
    val filter: FilterMode = FilterMode.MAGIC,
    val rev: Int = 0
)

data class DocItem(
    val file: File,
    val name: String,
    val pages: Int,
    val size: Long,
    val modified: Long,
    val thumb: File?
)
