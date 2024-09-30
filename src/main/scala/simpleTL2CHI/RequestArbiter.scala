package simpleTL2CHI

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tilelink.TLMessages._

class RequestArbiter()(implicit p: Parameters) extends SimpleTL2CHIModule {
    val io = IO(new Bundle {
        val a             = Flipped(Decoupled(new TLBundleA(tlBundleParams)))
        val alloc_s1      = Decoupled(new TaskBundle)
        val machineStatus = Vec(nrMachine, Input(new MachineStatus))
    })

    assert(!(io.a.fire && io.a.bits.size =/= 3.U), "Invalid size for request")

    val reqIsGet     = io.a.bits.opcode === Get
    val addrMatchVec = VecInit(io.machineStatus.map(s => s.address === io.a.bits.address && s.state =/= MachineState.IDLE)).asUInt
    val blockVec     = VecInit(io.machineStatus.map(s => s.state =/= MachineState.IDLE && s.state <= MachineState.RETURN_ACK)).asUInt
    val blockA       = (addrMatchVec & blockVec).orR
    io.a.ready := io.alloc_s1.ready && !blockA

    io.alloc_s1.valid        := io.a.valid
    io.alloc_s1.bits.address := io.a.bits.address
    io.alloc_s1.bits.opcode  := io.a.bits.opcode
    io.alloc_s1.bits.param   := io.a.bits.param
    io.alloc_s1.bits.source  := io.a.bits.source
    io.alloc_s1.bits.data    := io.a.bits.data
    io.alloc_s1.bits.mask    := io.a.bits.mask
}
