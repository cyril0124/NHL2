package AsyncBridgeCHI

import chisel3._
import chisel3.util._
import SimpleL2.Bundles._
import Utils.GenerateVerilog
import freechips.rocketchip.util._
import freechips.rocketchip.util.{AsyncResetSynchronizerShiftReg, AsyncQueue, AsyncQueueParams}

object Config {
    // Basic CHI bus configuration
    // @formatter:off
    val chiBundleParams = CHIBundleParameters(
        nodeIdBits = 7,
        addressBits = 44,
        dataBits = 256,
        dataCheck = false
    )
    // @formatter:on

    // Define the max credit count for each CHI channel
    val maxCreditTXREQ = 4
    val maxCreditTXDAT = 4
    val maxCreditTXRSP = 4
    val maxCreditRXDAT = 4
    val maxCreditRXRSP = 4
    val maxCreditRXSNP = 4

    // Number of sync register used by LinkCtrl signals
    val numSyncReg = 3
}

class AsyncBridgeCHI extends RawModule {
    val enq_clock = IO(Input(Clock()))
    val enq_reset = IO(Input(Reset()))
    val deq_clock = IO(Input(Clock()))
    val deq_reset = IO(Input(Reset()))
    val io = IO(new Bundle {
        val linkCtrl_enq    = Flipped(new CHILinkCtrlIO())
        val linkCtrl_deq    = new CHILinkCtrlIO()
        val chi_enq         = CHIBundleUpstream(Config.chiBundleParams, true)
        val chi_deq         = CHIBundleDownstream(Config.chiBundleParams, true)
        val resetFinish_enq = Output(Bool())
        val resetFinish_deq = Output(Bool())
    })

    // A helper object for creating AsyncCreditBridge
    object AsyncConnect {
        //
        // case class AsyncQueueParams: (default parameters)
        //      depth: Int      = 8
        //      sync: Int       = 3
        //      safe: Boolean   = true    [[If safe is true, then effort is made to resynchronize the crossing indices when either side is reset.
        //                                  This makes it safe/possible to reset one side of the crossing (but not the other) when the queue is empty.]]
        //      narrow: Boolean = false   [[If narrow is true then the read mux is moved to the source side of the crossing.
        //                                  This reduces the number of level shifters in the case where the clock crossing is also a voltage crossing,
        //                                  at the expense of a combinational path from the sink to the source and back to the sink.]]
        //

        // Factory method for creating AsyncQueue between two clock domain(fifo structure)
        // clock domains:
        //      enq_clock   <--\           /--> deq_clock
        //                     |AsyncQueue|
        //      enq_reset  <--/           \--> deq_reset
        //
        def apply[T <: Data](in: CHIChannelIO[T], in_clock: Clock, in_reset: Reset, out_clock: Clock, out_reset: Reset, name: String = "Unknown", depth: Int = 4, sync: Int = 3): CHIChannelIO[T] = {
            val out    = WireInit(0.U.asTypeOf(chiselTypeOf(in)))
            val params = AsyncQueueParams(depth, sync)
            val q      = Module(new AsyncQueue(chiselTypeOf(in.flit), params))
            q.io.enq_clock := in_clock
            q.io.enq_reset := in_reset
            q.io.deq_clock := out_clock
            q.io.deq_reset := out_reset
            q.io.enq.bits  := in.flit
            q.io.enq.valid := in.flitv

            // q.io.enq.ready  ==> DontCare
            withClockAndReset(enq_clock, enq_reset) {
                assert(!(!q.io.enq.ready && in.flitv), s"AsyncConnect [${name}] enq when AsnycQueue is not ready!")
            }

            out.flit       := q.io.deq.bits
            out.flitv      := q.io.deq.valid
            q.io.deq.ready := true.B
            out
        }

        // Creating a 1-bit AsyncQueue between two clock domain, only used for bit pulse signals(e.g. lcrdv in CHI)
        def bitPulseConnect[T <: Data](in: Bool, in_clock: Clock, in_reset: Reset, out_clock: Clock, out_reset: Reset, name: String = "Unknown", depth: Int = 4, sync: Int = 3): Bool = {
            val out    = WireInit(false.B)
            val params = AsyncQueueParams(depth, sync)
            val q      = Module(new AsyncQueue(UInt(0.W), params))
            q.io.enq_clock := in_clock
            q.io.enq_reset := in_reset
            q.io.deq_clock := out_clock
            q.io.deq_reset := out_reset
            q.io.enq.bits <> DontCare
            q.io.enq.valid := in

            // q.io.enq.ready  ==> DontCare
            withClockAndReset(enq_clock, enq_reset) {
                assert(!(!q.io.enq.ready && in), s"AsyncConnect [${name}] enq when AsnycQueue is not ready!")
            }

            out            := q.io.deq.valid
            q.io.deq.ready := true.B
            out
        }
    }

    //
    // Input link control singals
    //
    withClockAndReset(enq_clock, enq_reset) {
        //
        // Below is a typical synchronizer with two registers
        //                                       │
        //                    ┌────┐  ┌────┐     │
        //  output signal ◄───┤ \/ │◄─┤ \/ │◄────│────── input signal
        //                    │    │  │    │     │
        //                    └────┘  └────┘     │
        //                     output clock      │
        //
        io.linkCtrl_enq.rxsactive   := SynchronizerShiftReg(io.linkCtrl_deq.rxsactive, Config.numSyncReg, Some("sync_LinkCtrl_rxsactive"))
        io.linkCtrl_enq.txactiveack := SynchronizerShiftReg(io.linkCtrl_deq.txactiveack, Config.numSyncReg, Some("sync_LinkCtrl_txactiveack"))
        io.linkCtrl_enq.rxactivereq := SynchronizerShiftReg(io.linkCtrl_deq.rxactivereq, Config.numSyncReg, Some("sync_LinkCrtl_rxactivereq"))

        io.chi_enq.rxdat.flitpend := SynchronizerShiftReg(io.chi_deq.rxdat.flitpend, Config.numSyncReg, Some("sync_enq_rxdat_flitpend"))
        io.chi_enq.rxrsp.flitpend := SynchronizerShiftReg(io.chi_deq.rxrsp.flitpend, Config.numSyncReg, Some("sync_enq_rxrsp_flitpend"))
        io.chi_enq.rxsnp.flitpend := SynchronizerShiftReg(io.chi_deq.rxsnp.flitpend, Config.numSyncReg, Some("sync_enq_rxsnp_flitpend"))

        val RESET_FINISH_MAX       = 100
        val resetFinishCounter_enq = withReset(enq_reset.asAsyncReset)(RegInit(0.U((log2Ceil(RESET_FINISH_MAX) + 1).W)))
        when(resetFinishCounter_enq < RESET_FINISH_MAX.U) {
            resetFinishCounter_enq := resetFinishCounter_enq + 1.U
        }
        io.resetFinish_enq := resetFinishCounter_enq >= RESET_FINISH_MAX.U
    }

    //
    // CHI TX Channel: responsible for receiving L-Credit
    //
    io.chi_deq.txreq <> AsyncConnect(io.chi_enq.txreq, enq_clock, enq_reset, deq_clock, deq_reset, "enq_txreq_to_deq_txreq", Config.maxCreditTXREQ)
    io.chi_deq.txdat <> AsyncConnect(io.chi_enq.txdat, enq_clock, enq_reset, deq_clock, deq_reset, "enq_txdat_to_deq_txdat", Config.maxCreditTXDAT)
    io.chi_deq.txrsp <> AsyncConnect(io.chi_enq.txrsp, enq_clock, enq_reset, deq_clock, deq_reset, "enq_txrsp_to_deq_txrsp", Config.maxCreditTXRSP)

    io.chi_enq.txreq.lcrdv <> AsyncConnect.bitPulseConnect(io.chi_deq.txreq.lcrdv, deq_clock, deq_reset, enq_clock, enq_reset, "deq_txreq_lcrdv_to_enq_txreq_lcrdv", Config.maxCreditTXREQ)
    io.chi_enq.txdat.lcrdv <> AsyncConnect.bitPulseConnect(io.chi_deq.txdat.lcrdv, deq_clock, deq_reset, enq_clock, enq_reset, "deq_txdat_lcrdv_to_enq_txdat_lcrdv", Config.maxCreditTXDAT)
    io.chi_enq.txrsp.lcrdv <> AsyncConnect.bitPulseConnect(io.chi_deq.txrsp.lcrdv, deq_clock, deq_reset, enq_clock, enq_reset, "deq_txrsp_lcrdv_to_enq_txrsp_lcrdv", Config.maxCreditTXRSP)

    //
    // CHI RX Channel: responsible for sending L-Credit
    //
    io.chi_enq.rxdat <> AsyncConnect(io.chi_deq.rxdat, deq_clock, deq_reset, enq_clock, enq_reset, "deq_rxdat_to_enq_rxdat", Config.maxCreditRXDAT)
    io.chi_enq.rxrsp <> AsyncConnect(io.chi_deq.rxrsp, deq_clock, deq_reset, enq_clock, enq_reset, "deq_rxrsp_to_enq_rxrsp", Config.maxCreditRXRSP)
    io.chi_enq.rxsnp <> AsyncConnect(io.chi_deq.rxsnp, deq_clock, deq_reset, enq_clock, enq_reset, "deq_rxsnp_to_enq_rxsnp", Config.maxCreditRXSNP)

    io.chi_deq.rxdat.lcrdv <> AsyncConnect.bitPulseConnect(io.chi_enq.rxdat.lcrdv, enq_clock, enq_reset, deq_clock, deq_reset, "enq_rxdat_lcrdv_to_deq_rxdat_lcrdv", Config.maxCreditRXDAT)
    io.chi_deq.rxrsp.lcrdv <> AsyncConnect.bitPulseConnect(io.chi_enq.rxrsp.lcrdv, enq_clock, enq_reset, deq_clock, deq_reset, "enq_rxrsp_lcrdv_to_deq_rxrsp_lcrdv", Config.maxCreditRXRSP)
    io.chi_deq.rxsnp.lcrdv <> AsyncConnect.bitPulseConnect(io.chi_enq.rxsnp.lcrdv, enq_clock, enq_reset, deq_clock, deq_reset, "enq_rxsnp_lcrdv_to_deq_rxsnp_lcrdv", Config.maxCreditRXSNP)

    //
    // Output link controls
    //
    withClockAndReset(deq_clock, deq_reset) {
        // chi power ctrl
        io.linkCtrl_deq.txsactive   := SynchronizerShiftReg(io.linkCtrl_enq.txsactive, Config.numSyncReg, Some("sync_LinkCtrl_txsactive"))
        io.linkCtrl_deq.rxactiveack := SynchronizerShiftReg(io.linkCtrl_enq.rxactiveack, Config.numSyncReg, Some("sync_LinkCtrl_rxactiveack"))
        io.linkCtrl_deq.txactivereq := SynchronizerShiftReg(io.linkCtrl_enq.txactivereq, Config.numSyncReg, Some("sync_LinkCrtl_txactivereq"))

        // chi tx flitpend
        io.chi_deq.txreq.flitpend := SynchronizerShiftReg(io.chi_enq.txreq.flitpend, Config.numSyncReg, Some("sync_enq_txreq_flitpend"))
        io.chi_deq.txdat.flitpend := SynchronizerShiftReg(io.chi_enq.txdat.flitpend, Config.numSyncReg, Some("sync_enq_txdat_flitpend"))
        io.chi_deq.txrsp.flitpend := SynchronizerShiftReg(io.chi_enq.txrsp.flitpend, Config.numSyncReg, Some("sync_enq_txrsp_flitpend"))

        // chi rx flitpend
        io.chi_enq.rxdat.flitpend := SynchronizerShiftReg(io.chi_deq.rxdat.flitpend, Config.numSyncReg, Some("sync_deq_rxdat_flitpend"))
        io.chi_enq.rxrsp.flitpend := SynchronizerShiftReg(io.chi_deq.rxrsp.flitpend, Config.numSyncReg, Some("sync_deq_rxrsp_flitpend"))
        io.chi_enq.rxsnp.flitpend := SynchronizerShiftReg(io.chi_deq.rxsnp.flitpend, Config.numSyncReg, Some("sync_deq_rxsnp_flitpend"))

        val RESET_FINISH_MAX       = 100
        val resetFinishCounter_deq = withReset(deq_reset.asAsyncReset)(RegInit(0.U((log2Ceil(RESET_FINISH_MAX) + 1).W)))
        when(resetFinishCounter_deq < RESET_FINISH_MAX.U) {
            resetFinishCounter_deq := resetFinishCounter_deq + 1.U
        }
        io.resetFinish_deq := resetFinishCounter_deq >= RESET_FINISH_MAX.U
    }

    dontTouch(enq_clock)
    dontTouch(deq_clock)
    dontTouch(enq_reset)
    dontTouch(deq_reset)
    dontTouch(io)
}

object AsyncBridgeCHI extends App {

    class AsyncBridgeCHI_TB extends Module {
        val deq_clock = IO(Input(Clock()))
        val deq_reset = IO(Input(Reset()))
        val io = IO(new Bundle {
            val linkCtrl_enq    = Flipped(new CHILinkCtrlIO())
            val linkCtrl_deq    = new CHILinkCtrlIO()
            val chi_enq         = CHIBundleUpstream(Config.chiBundleParams, true)
            val chi_deq         = CHIBundleDownstream(Config.chiBundleParams, true)
            val resetFinish_enq = Output(Bool())
            val resetFinish_deq = Output(Bool())
        })

        val bridge = Module(new AsyncBridgeCHI)
        bridge.enq_clock := clock
        bridge.enq_reset := reset
        bridge.deq_clock := deq_clock
        bridge.deq_reset := deq_reset
        bridge.io <> io

        dontTouch(bridge.io)
    }

    GenerateVerilog(args, () => new AsyncBridgeCHI_TB, name = "AsyncBridgeCHI_TB", split = false)
}
