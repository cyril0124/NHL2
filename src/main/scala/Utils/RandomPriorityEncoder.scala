package Utils

import chisel3._
import chisel3.util._
import freechips.rocketchip.util.Random
import freechips.rocketchip.util.SeqToAugmentedSeq

object RandomPriorityEncoderOH {

    /**
     * Applies random priority encoding on a sequence of `Bool` values.
     * It generates a Linear Feedback Shift Register (LFSR) based on the input sequence length and the randomness flag.
     * Converts the random number to One-Hot encoding and performs a bitwise AND operation with the input sequence as `UInt`.
     * Checks if there is a match by performing an OR operation on the resulting match vector.
     * Asserts that the count of set bits in the match vector is at most 1.
     * Based on the presence of a match, selects and returns either the match vector or the result of `PriorityEncoderOH` on the input sequence.
     *
     * @param in        The sequence of `Bool` values.
     * @param doRandom  A boolean indicating whether randomness is involved.
     * @return          The encoded `UInt` value.
     */
    def apply(in: Seq[Bool], doRandom: Bool): UInt = {
        val lfsr         = random.LFSR(in.length, doRandom)
        val lfsrOH       = UIntToOH(Random(in.length, lfsr))
        val lfsrMatchVec = lfsrOH & in.asUInt
        val lfsrHasMatch = lfsrMatchVec.orR
        assert(PopCount(lfsrMatchVec) <= 1.U, "lfsrMatchVec: 0b%b validVec: 0b%b lfsrOH: 0b%b", PopCount(lfsrMatchVec), in.asUInt, lfsrOH)
        Mux(lfsrHasMatch, lfsrMatchVec, PriorityEncoderOH(in.asUInt))
    }

    def apply(in: Bits, doRandom: Bool): UInt = apply(in.asBools, doRandom)
}

object RandomPriorityEncoder {

    /**
     * Applies random priority encoding on a sequence of `Bool` values.
     * It generates a Linear Feedback Shift Register (LFSR) based on the input sequence length and the randomness flag.
     * Converts the random number to One-Hot encoding and performs a bitwise AND operation with the input sequence as `UInt`.
     * Checks if there is a match by performing an OR operation on the resulting match vector.
     * Asserts that the count of set bits in the match vector is at most 1.
     * Based on the presence of a match, selects the match vector or the result of `PriorityEncoderOH` on the input sequence, and then converts the selected result from One-Hot encoding to `UInt` and returns it.
     *
     * @param in        The sequence of `Bool` values.
     * @param doRandom  A boolean indicating whether randomness is involved.
     * @return          The encoded `UInt` value.
     */
    def apply(in: Seq[Bool], doRandom: Bool): UInt = {
        val lfsr         = random.LFSR(in.length, doRandom)
        val lfsrOH       = UIntToOH(Random(in.length, lfsr))
        val lfsrMatchVec = lfsrOH & in.asUInt
        val lfsrHasMatch = lfsrMatchVec.orR
        assert(PopCount(lfsrMatchVec) <= 1.U, "lfsrMatchVec: 0b%b validVec: 0b%b lfsrOH: 0b%b", PopCount(lfsrMatchVec), in.asUInt, lfsrOH)
        OHToUInt(Mux(lfsrHasMatch, lfsrMatchVec, PriorityEncoderOH(in.asUInt)))
    }

    def apply(in: Bits, doRandom: Bool): UInt = apply(in.asBools, doRandom)
}
