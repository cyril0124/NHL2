package Utils

import chisel3._
import chisel3.util._
import Utils.GenerateVerilog
import chisel3.experimental.{SourceInfo, SourceLine}
import freechips.rocketchip.util.SeqToAugmentedSeq

class IDPoolAlloc(idBits: Int) extends Bundle {
    val valid = Input(Bool())
    val idOut = Output(UInt(idBits.W))
}

class IDPoolFree(idBits: Int) extends Bundle {
    val valid = Input(Bool())
    val idIn  = Input(UInt(idBits.W))
}

class IDPool(ids: Set[Int], maxLeakCnt: Int = 10000)(implicit s: SourceInfo) extends Module {
    val idBits    = log2Ceil(ids.toSeq.max) + 1
    val sortedIds = ids.toSeq.sorted(Ordering[Int])
    val nrIds     = sortedIds.size

    val io = IO(new Bundle {
        val alloc = new IDPoolAlloc(idBits)
        val free  = new IDPoolFree(idBits)

        val full    = Output(Bool())
        val freeCnt = Output((UInt(log2Ceil(nrIds + 1).W)))
    })

    val debugInfo = s match {
        case SourceLine(filename, line, col) => s"($filename:$line:$col)"
        case _                               => ""
    }
    println(s"[${this.getClass().toString()}] create IDPool with ids:${sortedIds} at ${debugInfo}")

    val allocIdx = RegInit(0.U(log2Ceil(nrIds).W))
    val freeIdx  = RegInit(0.U(log2Ceil(nrIds).W))

    val validIds = VecInit(sortedIds.map(_.U))
    val freeList = RegInit(validIds)

    val allocatedList = RegInit(VecInit(Seq.fill(nrIds)(false.B))) // Register for tracking allocated IDs

    io.alloc.idOut := freeList(allocIdx)

    /** Allocation logic
     * - Updates allocation index and free count
     * - Marks the allocated ID in allocatedList
     * - Ensures that the pool is not full when allocation is requested
     */
    when(io.alloc.valid) {
        allocIdx := Mux(allocIdx === (nrIds - 1).U, 0.U, allocIdx + 1.U)

        val allocVec = VecInit(validIds.map(_ === io.alloc.idOut)).reverse.asUInt
        allocVec.asBools.zip(allocatedList.reverse).foreach { case (alloc, idBit) =>
            idBit := idBit | alloc
            when(alloc) {
                assert(!idBit, "idOut: %d allocVec:%b", io.alloc.idOut, allocVec)
            }
        }

        assert(!io.full, "IDPool is full!")
    }

    /** Release logic
     * - Updates the free list and free count
     * - Clears the allocated flag in allocatedList
     * - Ensures the released ID is valid and was previously allocated
     */
    when(io.free.valid) {
        freeList(freeIdx) := io.free.idIn
        freeIdx           := Mux(freeIdx === (nrIds - 1).U, 0.U, freeIdx + 1.U)

        // Update allocatedList to mark the ID as free
        val allocatedMatchOH = VecInit(validIds.map(_ === io.free.idIn))
        allocatedMatchOH.zip(allocatedList).foreach { case (allotedMatch, idBit) =>
            when(allotedMatch) {
                idBit := !allotedMatch
                assert(idBit, s"idIn is not an allocated id! idIn:%d validIds:${sortedIds} %b %b", io.free.idIn, allocatedList.asUInt, allocatedMatchOH.asUInt)
            }
        }
        // Ensure only one match and valid ID
        assert(PopCount(allocatedMatchOH) <= 1.U)
        assert(VecInit(validIds.map(_ === io.free.idIn)).asUInt.orR, s"idIn is not a valid ID, io.idIn:%d, validIds:${sortedIds}", io.free.idIn)
    }

    val freeCnt = RegInit(nrIds.U(log2Ceil(nrIds + 1).W))
    when(io.alloc.valid && !io.free.valid) {
        freeCnt := freeCnt - 1.U
        assert(freeCnt =/= 0.U, "alloc asserted when pool is full!")
    }.elsewhen(!io.alloc.valid && io.free.valid) {
        freeCnt := freeCnt + 1.U
        assert(freeCnt <= nrIds.U, s"freeCnt:%d nrIds:${nrIds}", freeCnt)
    } // otherwise freeCnt remians unchanged

    /** Leak checker to monitor allocated IDs for potential leaks
     * @param allocated Allocated flag for each ID
     * @param notAllocated Not allocated flag for each ID
     * @param maxCount Maximum count for leak detection
     */
    allocatedList.zipWithIndex.foreach { case (allocated, i) =>
        LeakChecker(allocated, ~allocated, Some(s"IDPool_allocated_idx_${i}_id_${sortedIds(i)}"), maxCount = maxLeakCnt)
    }

    io.full    := (freeCnt === 0.U)
    io.freeCnt := freeCnt

    dontTouch(io)
}

object IDPool extends App {
    GenerateVerilog(args, () => new IDPool((16 until 20).toSet, maxLeakCnt = 100), name = "IDPool", split = false)
}
