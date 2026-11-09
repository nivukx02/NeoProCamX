package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UiTechnologyNamePolicyTest {
    @Test
    fun ndrProductNamesArePresentedAsXdrWithoutChangingOtherTechnologies() {
        assertEquals("XDR", UiTechnologyNamePolicy.present("NDR"))
        assertEquals("XDR10+", UiTechnologyNamePolicy.present("NDR10+"))
        assertEquals("XDR10+", UiTechnologyNamePolicy.present("NDR10_PLUS"))
        assertEquals("Rec.2020-XDR10+", UiTechnologyNamePolicy.present("Rec.2020-NDR10+"))
        assertEquals("Existing XDR intermediates", UiTechnologyNamePolicy.present("Existing NDR intermediates"))
        assertEquals("NIVUKX Vision", UiTechnologyNamePolicy.present("NIVUKX Vision"))
    }
}
