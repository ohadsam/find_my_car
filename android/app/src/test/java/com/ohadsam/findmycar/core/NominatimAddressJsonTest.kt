package com.ohadsam.findmycar.core

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Same cases as tests/unit/geocoder.test.js — the two parsers must agree. */
@RunWith(RobolectricTestRunner::class)
class NominatimAddressJsonTest {
    private fun parse(s: String) = NominatimAddressJson.parse(s)

    @Test
    fun `street address`() {
        assertEquals(
            NominatimAddressJson.Result.Found(NativeAddress("הרצל 12 תל אביב", "הרצל", "12", "תל אביב", "לב העיר")),
            parse("""{"address":{"road":"הרצל","house_number":"12","city":"תל אביב","suburb":"לב העיר"}}"""),
        )
    }

    @Test
    fun `village with no street`() {
        assertEquals(
            NominatimAddressJson.Result.Found(NativeAddress("נהלל", null, null, "נהלל", null)),
            parse("""{"address":{"village":"נהלל"}}"""),
        )
    }

    @Test
    fun `open area with only a region is none`() {
        assertEquals(NominatimAddressJson.Result.None, parse("""{"display_name":"מועצה","address":{"county":"x"}}"""))
    }

    @Test
    fun `unable to geocode is none`() {
        assertEquals(NominatimAddressJson.Result.None, parse("""{"error":"Unable to geocode"}"""))
    }

    @Test
    fun `empty body is failed, not none`() {
        assertEquals(NominatimAddressJson.Result.Failed, parse("{}"))
    }

    @Test
    fun `garbage is failed`() {
        assertEquals(NominatimAddressJson.Result.Failed, parse("<html>"))
    }
}
