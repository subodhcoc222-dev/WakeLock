package com.anekar.wakelock.domain.math

import com.anekar.wakelock.data.model.MissionDifficulty
import kotlin.math.pow
import kotlin.random.Random

data class MathProblem(
    val terms: List<Long>,
    val correctSum: Long
)

object MathMissionGenerator {
    private const val CHAIN_LENGTH = 6

    fun generateProblem(difficulty: MissionDifficulty): MathProblem {
        val digits = difficulty.digits
        val minVal = if (digits == 1) 1L else (10.0.pow((digits - 1).toDouble())).toLong()
        val maxVal = (10.0.pow(digits.toDouble())).toLong() - 1L

        val terms = mutableListOf<Long>()
        var sum = 0L

        for (i in 0 until CHAIN_LENGTH) {
            val num = Random.nextLong(minVal, maxVal + 1)
            terms.add(num)
            sum += num
        }

        return MathProblem(
            terms = terms,
            correctSum = sum
        )
    }

    fun validateAnswer(userInput: String, correctSum: Long): Boolean {
        val cleaned = userInput.trim().replace(",", "")
        val parsed = cleaned.toLongOrNull() ?: return false
        return parsed == correctSum
    }
}
