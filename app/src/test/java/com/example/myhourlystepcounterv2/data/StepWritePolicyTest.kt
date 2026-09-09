package com.example.myhourlystepcounterv2.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The atomic save keeps the higher value, so some writes never land. Whether a write landed
 * decides whether its author may claim the hour's sourcePath — a rejected writer stamping its
 * name over the value that actually persisted is how the anomaly record ends up blaming the
 * wrong code path.
 */
class StepWritePolicyTest {

    @Test
    fun firstWriteForAnHourPersists() {
        assertTrue(StepWritePolicy.persists(existing = null, incoming = 0))
    }

    @Test
    fun higherWritePersists() {
        assertTrue(StepWritePolicy.persists(existing = 100, incoming = 250))
    }

    @Test
    fun lowerWriteIsRejected() {
        assertFalse(StepWritePolicy.persists(existing = 10000, incoming = 0))
    }

    @Test
    fun equalWriteIsRejected() {
        // Nothing changes, so the existing row keeps its author.
        assertFalse(StepWritePolicy.persists(existing = 475, incoming = 475))
    }
}
