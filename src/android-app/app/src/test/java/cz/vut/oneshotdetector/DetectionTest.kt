/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector

import cz.vut.oneshotdetector.model.inference.Detection
import org.junit.Assert.*
import org.junit.Test

class DetectionTest {

    private fun detection(
        label: String = "cat",
        score: Float = 0.9f,
        box: FloatArray = floatArrayOf(0f, 0f, 1f, 1f),
        alphaScore: Float = 0.8f,
        similarityCosine: Float? = null
    ) = Detection(label, score, box, alphaScore, similarityCosine)

    // equals

    @Test
    fun `identical detections are equal`() {
        val a = detection()
        val b = detection()
        assertEquals(a, b)
    }

    @Test
    fun `detections with different labels are not equal`() {
        assertNotEquals(detection(label = "cat"), detection(label = "dog"))
    }

    @Test
    fun `detections with different scores are not equal`() {
        assertNotEquals(detection(score = 0.9f), detection(score = 0.5f))
    }

    @Test
    fun `detections with different boxes are not equal`() {
        val a = detection(box = floatArrayOf(0f, 0f, 1f, 1f))
        val b = detection(box = floatArrayOf(0f, 0f, 2f, 2f))
        assertNotEquals(a, b)
    }

    @Test
    fun `detections with different alphaScore are not equal`() {
        assertNotEquals(detection(alphaScore = 0.8f), detection(alphaScore = 0.5f))
    }

    @Test
    fun `detections differ when one has cosine similarity and the other does not`() {
        assertNotEquals(detection(similarityCosine = null), detection(similarityCosine = 0.7f))
    }

    @Test
    fun `detections with same cosine similarity are equal`() {
        assertEquals(detection(similarityCosine = 0.7f), detection(similarityCosine = 0.7f))
    }

    @Test
    fun `detection is not equal to null`() {
        assertNotEquals(detection(), null)
    }

    @Test
    fun `detection is not equal to different type`() {
        assertNotEquals(detection(), "not a detection")
    }

    // ── hashCode ──────────────────────────────────────────────────────────────

    @Test
    fun `equal detections have the same hashCode`() {
        val a = detection()
        val b = detection()
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `detections with cosine similarity differ in hashCode when similarity differs`() {
        val a = detection(similarityCosine = 0.5f)
        val b = detection(similarityCosine = 0.9f)
        assertNotEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `detection can be used as a map key`() {
        val d = detection()
        val map = mapOf(d to "value")
        assertEquals("value", map[detection()])
    }

    @Test
    fun `equals is reflexive`() {
        val d = detection()
        assertEquals(d, d)
    }

    @Test
    fun `equals is symmetric`() {
        val a = detection()
        val b = detection()
        assertEquals(a, b)
        assertEquals(b, a)
    }
}