package org.nomadpocket.app

import org.junit.Assert.*
import org.junit.Test

class WorksWithTest {
    @Test fun shownOnlyToRelatedUsersAndHidesWhenDismissed() {
        assertFalse(WorksWith.showWarWalker(false, false, false))
        assertTrue(WorksWith.showWarWalker(true, false, false))
        assertTrue(WorksWith.showWarWalker(false, true, false))
        assertFalse(WorksWith.showWarWalker(true, true, true))
    }
}
