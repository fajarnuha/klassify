package com.fajarnuha.klassify.recipe

internal fun comparePackVersions(left: String, right: String): Int {
    val leftCore = left.substringBefore('-').split('.')
    val rightCore = right.substringBefore('-').split('.')
    for ((a, b) in leftCore.zip(rightCore)) {
        val order = a.toBigInteger().compareTo(b.toBigInteger())
        if (order != 0) return order
    }

    val leftPrerelease = left.substringAfter('-', "")
    val rightPrerelease = right.substringAfter('-', "")
    if (leftPrerelease == rightPrerelease) return 0
    if (leftPrerelease.isEmpty()) return 1
    if (rightPrerelease.isEmpty()) return -1

    val leftIdentifiers = leftPrerelease.split('.')
    val rightIdentifiers = rightPrerelease.split('.')
    for ((a, b) in leftIdentifiers.zip(rightIdentifiers)) {
        val order = comparePrereleaseIdentifiers(a, b)
        if (order != 0) return order
    }
    return leftIdentifiers.size.compareTo(rightIdentifiers.size)
}

private fun comparePrereleaseIdentifiers(left: String, right: String): Int {
    val leftNumber = left.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toBigInteger()
    val rightNumber = right.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toBigInteger()
    return when {
        leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
        leftNumber != null -> -1
        rightNumber != null -> 1
        else -> left.compareTo(right)
    }
}
