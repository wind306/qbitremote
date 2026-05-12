package com.hjw.qbremote.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardLayoutFollowupSupportTest {

    @Test
    fun buildCompactCountrySummaryItems_keepsTopThreeAsSeparateInlineItems() {
        val entries = listOf(
            ResolvedDashboardBarEntry(label = "US", value = 1_200_000_000L, valueText = "1.2 GB"),
            ResolvedDashboardBarEntry(label = "JP", value = 860_000_000L, valueText = "860 MB"),
            ResolvedDashboardBarEntry(label = "SG", value = 430_000_000L, valueText = "430 MB"),
            ResolvedDashboardBarEntry(label = "DE", value = 120_000_000L, valueText = "120 MB"),
        )

        val summaryItems = buildCompactCountrySummaryItems(entries)

        assertEquals(
            listOf(
                CompactCountrySummaryItem(labelText = "US", valueText = "1.2 GB"),
                CompactCountrySummaryItem(labelText = "JP", valueText = "860 MB"),
                CompactCountrySummaryItem(labelText = "SG", valueText = "430 MB"),
            ),
            summaryItems,
        )
    }

    @Test
    fun buildDashboardDisplayCards_keepsDistributionChartsAsSeparateDisplayItems() {
        val displayCards = buildDashboardDisplayCards(
            listOf(
                DashboardChartCard.COUNTRY_FLOW,
                DashboardChartCard.SIZE_DISTRIBUTION,
                DashboardChartCard.SHARE_RATIO_DISTRIBUTION,
                DashboardChartCard.DAILY_UPLOAD,
            ),
        )

        assertEquals(4, displayCards.size)
        assertEquals(DashboardChartCard.COUNTRY_FLOW, displayCards[0].owner)
        assertEquals(listOf(DashboardChartCard.COUNTRY_FLOW), displayCards[0].representedCards)
        assertEquals(DashboardChartCard.SIZE_DISTRIBUTION, displayCards[1].owner)
        assertEquals(listOf(DashboardChartCard.SIZE_DISTRIBUTION), displayCards[1].representedCards)
        assertEquals(DashboardChartCard.SHARE_RATIO_DISTRIBUTION, displayCards[2].owner)
        assertEquals(listOf(DashboardChartCard.SHARE_RATIO_DISTRIBUTION), displayCards[2].representedCards)
        assertEquals(DashboardChartCard.DAILY_UPLOAD, displayCards[3].owner)
        assertEquals(listOf(DashboardChartCard.DAILY_UPLOAD), displayCards[3].representedCards)
    }

    @Test
    fun reorderDashboardCardOrderForDisplay_movesOnlyTheSelectedDistributionCard() {
        val order = listOf(
            DashboardChartCard.COUNTRY_FLOW,
            DashboardChartCard.SIZE_DISTRIBUTION,
            DashboardChartCard.SHARE_RATIO_DISTRIBUTION,
            DashboardChartCard.DAILY_UPLOAD,
        )
        val displayCards = buildDashboardDisplayCards(order)

        val reordered = reorderDashboardCardOrderForDisplay(
            order = order,
            displayCards = displayCards,
            owner = DashboardChartCard.SHARE_RATIO_DISTRIBUTION,
            targetIndex = 1,
        )

        assertEquals(
            listOf(
                DashboardChartCard.COUNTRY_FLOW,
                DashboardChartCard.SHARE_RATIO_DISTRIBUTION,
                DashboardChartCard.SIZE_DISTRIBUTION,
                DashboardChartCard.DAILY_UPLOAD,
            ),
            reordered,
        )
    }

    @Test
    fun applyDashboardDisplayCardVisibility_hidesOnlyTheSelectedDistributionCard() {
        val visibleKeys = linkedSetOf(
            DashboardChartCard.COUNTRY_FLOW.storageKey,
            DashboardChartCard.SIZE_DISTRIBUTION.storageKey,
            DashboardChartCard.SHARE_RATIO_DISTRIBUTION.storageKey,
            DashboardChartCard.DAILY_UPLOAD.storageKey,
        )
        val displayCard = DashboardDisplayCardItem(
            owner = DashboardChartCard.SHARE_RATIO_DISTRIBUTION,
            representedCards = listOf(DashboardChartCard.SHARE_RATIO_DISTRIBUTION),
        )

        val updated = applyDashboardDisplayCardVisibility(
            visibleKeys = visibleKeys,
            displayCard = displayCard,
            visible = false,
        )

        assertEquals(
            linkedSetOf(
                DashboardChartCard.COUNTRY_FLOW.storageKey,
                DashboardChartCard.SIZE_DISTRIBUTION.storageKey,
                DashboardChartCard.DAILY_UPLOAD.storageKey,
            ),
            updated,
        )
    }

    @Test
    fun buildDashboardDisplayCards_keepsSingleDistributionCardStableWhenOnlyOneVisible() {
        val displayCards = buildDashboardDisplayCards(
            listOf(
                DashboardChartCard.COUNTRY_FLOW,
                DashboardChartCard.SHARE_RATIO_DISTRIBUTION,
            ),
        )

        assertEquals(
            listOf(
                DashboardChartCard.COUNTRY_FLOW,
                DashboardChartCard.SHARE_RATIO_DISTRIBUTION,
            ),
            displayCards.map { it.owner },
        )
        assertEquals(
            listOf(
                listOf(DashboardChartCard.COUNTRY_FLOW),
                listOf(DashboardChartCard.SHARE_RATIO_DISTRIBUTION),
            ),
            displayCards.map { it.representedCards },
        )
    }
}
