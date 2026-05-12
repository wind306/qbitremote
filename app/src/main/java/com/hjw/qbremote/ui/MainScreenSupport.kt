package com.hjw.qbremote.ui

import com.hjw.qbremote.data.AppTheme

internal fun buildSortedDistinctTrimmedStrings(values: List<String>): List<String> {
    return values
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
}

internal fun buildPageThemeSignature(
    appTheme: AppTheme,
    customBackgroundToneIsLight: Boolean,
    customBackgroundImagePath: String,
): String {
    return listOf(
        appTheme.name,
        customBackgroundToneIsLight.toString(),
        customBackgroundImagePath.trim(),
    ).joinToString("|")
}
