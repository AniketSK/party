package com.poweredbyautistics.party

/**
 * A name's light pattern, derived from its FNV-1a hash so the same name always looks the same.
 * Hues are 16-bit (0-65535 = full circle), as the firmware's ColorHSV expects.
 */
data class NameSignature(
    val hueA: Int,   // colour of LEDs whose mask bit is 1
    val hueB: Int,   // colour of the others; always 90-270° from hueA so the two contrast
    val mask: Int,   // 12 bits, bit i = LED i
    val speed: Int,  // 0-255; firmware breathes once every 1000 + speed*12 ms
)

fun signatureOf(name: String): NameSignature {
    var h = 0x811C9DC5.toInt()  // FNV-1a 32-bit offset basis
    for (b in name.trim().lowercase().encodeToByteArray()) h = (h xor (b.toInt() and 0xFF)) * 16777619
    val h2 = (h xor (h ushr 15)) * 0x9E3779B1.toInt()  // second word, so mask/speed don't track the hues
    val hueA = h and 0xFFFF
    val hueB = (hueA + 16384 + ((h ushr 16) and 0xFF) * 128) and 0xFFFF
    val mask = (h2 and 0xFFF).let { if (it == 0 || it == 0xFFF) 0xA5A else it }  // need both hues on the ring
    return NameSignature(hueA, hueB, mask, (h2 ushr 16) and 0xFF)
}
