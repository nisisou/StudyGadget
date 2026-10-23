package com.example.studycompanion.character

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** A constrained animation plan, not executable code or arbitrary bone transforms. */
@Serializable
data class MotionRecipe(
    val state: Int, val duration: Float = 8f, val speed: Float = 1f,
    val intensity: Float = 0.4f, val sway: Float = 0.04f, val head: Float = 0.18f,
    val arm: Float = 0.8f, val phase: Float = 0f
) {
    fun valid(): Boolean = state in 0..5 && duration.isFinite() && duration in 2f..30f &&
        speed.isFinite() && speed in 0.2f..2f && intensity.isFinite() && intensity in 0f..1f &&
        sway.isFinite() && sway in 0f..0.2f && head.isFinite() && head in 0f..0.5f &&
        arm.isFinite() && arm in 0f..1.5f && phase.isFinite() && phase in 0f..6.284f

    fun line(): String = listOf(state, duration, speed, intensity, sway, head, arm, phase).joinToString(" ")
}

object MotionPlanner {
    val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    fun validate(recipes: List<MotionRecipe>): List<MotionRecipe> {
        require(recipes.size in 6..96 && recipes.all { it.valid() }) { "Motion recipe out of bounds" }
        require((0..5).all { state -> recipes.any { it.state == state } }) { "All six states are required" }
        return recipes
    }

    fun generate(seed: Int, studySeconds: Long): List<MotionRecipe> {
        val random = Random(seed)
        return (0..5).flatMap { state ->
            (0..7).map {
                val calm = state == 1 || state == 5
                MotionRecipe(state, 5f + random.nextFloat()*10, if (calm) 0.35f + random.nextFloat()*0.35f else 0.7f+random.nextFloat()*0.6f,
                    if (calm) 0.15f+random.nextFloat()*0.25f else 0.4f+random.nextFloat()*0.5f,
                    if (calm) 0.015f else 0.03f+random.nextFloat()*0.07f,
                    0.1f+random.nextFloat()*0.2f, if (studySeconds >= 3600) 1.1f else 0.7f+random.nextFloat()*0.3f,
                    random.nextFloat()*6.28f)
            }
        }
    }

    fun decode(text: String): List<MotionRecipe> = validate(json.decodeFromString<List<MotionRecipe>>(text))

    fun decodeBank(bytes: ByteArray, skeleton: Long, revision: Int): List<MotionRecipe> {
        require(bytes.size <= 32768)
        val lines = bytes.toString(Charsets.US_ASCII).trim().lines()
        val header = lines.first().trim().split(Regex("\\s+"))
        require(header.size == 5 && header[0] == "SGMO" && header[1] == "1" &&
            header[2].toLong() == skeleton && header[3].toInt() == revision && header[4].toInt() == lines.size-1)
        return validate(lines.drop(1).map { line ->
            val p = line.trim().split(Regex("\\s+"))
            require(p.size == 8)
            MotionRecipe(p[0].toInt(),p[1].toFloat(),p[2].toFloat(),p[3].toFloat(),
                p[4].toFloat(),p[5].toFloat(),p[6].toFloat(),p[7].toFloat())
        })
    }

    fun encodeBank(skeleton: Long, revision: Int, recipes: List<MotionRecipe>): ByteArray {
        validate(recipes)
        return ("SGMO 1 $skeleton $revision ${recipes.size}\n" + recipes.joinToString("\n") { it.line() } + "\n").toByteArray(Charsets.US_ASCII)
    }
}
