package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import SimpleL2.Configs._
import SimpleL2.chi._

object LinkState {
    def STOP = "b00".U(2.W)
    def ACTIVATE = "b10".U(2.W)
    def RUN = "b11".U(2.W)
    def DEACTIVATE = "b01".U(2.W)
}

class CHIBridgeInput(implicit p: Parameters) extends L2Bundle {
    val chi = CHIBundleDecoupled(chiBundleParams)
}

class CHIBridgeOutput(implicit p: Parameters) extends L2Bundle {
    val chi         = CHIBundleDownstream(chiBundleParams)
    val chiLinkCtrl = new CHILinkCtrlIO()
}

class CHIBridge()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        // val in  = new CHIBridgeInput
        val out = new CHIBridgeOutput
        // TODO: val deactivateTxLink = Input(Bool())
        // TODO: ShutDown
    })

    io.out.chi         <> DontCare
    io.out.chiLinkCtrl <> DontCare

    val txactivereq = io.out.chiLinkCtrl.txactivereq
    val txactiveack = io.out.chiLinkCtrl.txactiveack
    val rxactivereq = io.out.chiLinkCtrl.rxactivereq
    val rxactiveack = io.out.chiLinkCtrl.rxactiveack

    val resetFinish = RegInit(false.B)
    resetFinish := true.B

    val txState = RegInit(LinkState.STOP)
    val rxState = RegInit(LinkState.STOP)
    dontTouch(txState)
    dontTouch(rxState)

    val updateTxState = WireInit(false.B)
    val updateRxState = WireInit(false.B)
    dontTouch(updateTxState)
    dontTouch(updateRxState)

    // @formatter:off
    updateTxState := (RegNext(RegNext(reset.asBool)) && resetFinish)                    ||
                     (txactivereq  && txactiveack && txState === LinkState.ACTIVATE)    ||
                     (!txactivereq && txactiveack && txState === LinkState.RUN)         ||
                     (!txactivereq && !txactiveack && txState === LinkState.DEACTIVATE)
                    //  TODO: LinkState.STOP
    // @formatter:on

    when(updateTxState && resetFinish) {
        when(txState === LinkState.STOP) {
            txState := LinkState.ACTIVATE
        }.elsewhen(txState === LinkState.ACTIVATE) {
            txState := LinkState.RUN
        }.elsewhen(txState === LinkState.RUN) {
            txState := LinkState.DEACTIVATE
        }.elsewhen(txState === LinkState.DEACTIVATE) {
            txState := LinkState.STOP
        }
    }

    // @formatter:off
    updateRxState := !reset.asBool && (
                        (rxactivereq && rxState === LinkState.STOP)        ||
                        (rxactivereq && rxactiveack && rxState === LinkState.ACTIVATE)     ||
                        (!rxactivereq && rxactiveack && rxState === LinkState.RUN)         ||
                        (!rxactivereq && !rxactiveack && rxState === LinkState.DEACTIVATE)
                    )
    // @formatter:on

    when(updateRxState) {
        when(rxState === LinkState.STOP) {
            rxState := LinkState.ACTIVATE
        }.elsewhen(rxState === LinkState.ACTIVATE) {
            rxState := LinkState.RUN
        }.elsewhen(rxState === LinkState.RUN) {
            rxState := LinkState.DEACTIVATE
        }.elsewhen(rxState === LinkState.DEACTIVATE) {
            rxState := LinkState.STOP
        }
    }

    io.out.chiLinkCtrl.txactivereq := txState === LinkState.ACTIVATE || txState === LinkState.RUN
    io.out.chiLinkCtrl.rxactiveack := rxactivereq && rxState === LinkState.STOP || rxState === LinkState.ACTIVATE || rxState === LinkState.RUN

    assert(!(txState === LinkState.RUN && txactivereq && !txactiveack), "txactiveack should keep high during RUN state")
    assert(!(txState === LinkState.ACTIVATE && !txactivereq), "txactivereq should keep high during ACTIVATE state")

    dontTouch(io)
}

object CHIBridge extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new CHIBridge()(config), name = "CHIBridge", split = false)
}
