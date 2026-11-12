package eu.kanade.tachiyomi.extension.es.tvymanga

import kotlinx.serialization.Serializable

@Serializable
class CategoryDto(
    val id: Int,
    val name: String,
    val slug: String,
    val count: Int = 0,
)

@Serializable
class RenderedDto(val rendered: String = "")

@Serializable
class PostDto(
    val id: Int = 0,
    val date: String = "",
    val slug: String = "",
    val title: RenderedDto = RenderedDto(),
    val categories: List<Int> = emptyList(),
    val content: RenderedDto? = null,
)
