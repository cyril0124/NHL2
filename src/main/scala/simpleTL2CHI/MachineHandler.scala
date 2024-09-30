package simpleTL2CHI

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import SimpleL2.chi._
import freechips.rocketchip.util.SeqToAugmentedSeq

class MachineHandler(implicit p: Parameters) extends SimpleTL2CHIModule {
    val io = IO(new Bundle {
        val allocOH  = Output(UInt(nrMachine.W))
        val alloc_s1 = Flipped(Decoupled(new TaskBundle))
        val status   = Vec(nrMachine, Output(new MachineStatus))
        val d        = Decoupled(new TLBundleD(tlBundleParams))
        val txreq    = Decoupled(new CHIBundleREQ(chiBundleParams))
        val txdat    = Decoupled(new CHIBundleDAT(chiBundleParams))
        val rxrsp    = Flipped(Decoupled(new CHIBundleRSP(chiBundleParams)))
        val rxdat    = Flipped(Decoupled(new CHIBundleDAT(chiBundleParams)))
    })

    io <> DontCare

    val machines = (0 until nrMachine).map(i => Module(new TransactionMachine))

    val freeVec = VecInit(machines.map(_.io.status.state === MachineState.IDLE)).asUInt
    val freeOH  = PriorityEncoderOH(freeVec)

    val rxrspMatchOH = UIntToOH(io.rxrsp.bits.txnID)(nrMachine - 1, 0)
    val rxdatMatchOH = UIntToOH(io.rxdat.bits.txnID)(nrMachine - 1, 0)

    machines.zipWithIndex.foreach { case (machine, i) =>
        machine.io             <> DontCare
        machine.io.alloc.valid := io.alloc_s1.valid && freeOH(i)
        machine.io.alloc.bits  := io.alloc_s1.bits
        machine.io.id          := i.U
        io.status(i)           := machine.io.status

        machine.io.resps.rxrsp.valid := io.rxrsp.fire && rxrspMatchOH(i)
        machine.io.resps.rxrsp.bits  := io.rxrsp.bits
        assert(!(io.rxrsp.fire && rxrspMatchOH(i) && !machine.io.resps.rxrsp.ready))

        machine.io.resps.rxdat.valid := io.rxdat.fire && rxdatMatchOH(i)
        machine.io.resps.rxdat.bits  := io.rxdat.bits
        assert(!(io.rxdat.fire && rxdatMatchOH(i) && !machine.io.resps.rxdat.ready))
    }

    io.alloc_s1.ready := freeVec.orR
    io.allocOH        := freeVec
    io.rxrsp.ready    := true.B
    io.rxdat.ready    := true.B

    fastArb(machines.map(_.io.tasks.d), io.d)
    fastArb(machines.map(_.io.tasks.txreq), io.txreq)
    fastArb(machines.map(_.io.tasks.txdat), io.txdat)
}
