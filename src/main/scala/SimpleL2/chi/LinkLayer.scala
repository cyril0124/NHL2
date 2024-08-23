package SimpleL2.chi

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import SimpleL2._

class LCredit2Decoupled[T <: Bundle](gen: T, lcreditNum: Int = 4, aggregateIO: Boolean = false) extends Module {
    val io = IO(new Bundle {
        val in             = Flipped(CHIChannelIO(gen.cloneType, aggregateIO))
        val out            = DecoupledIO(gen.cloneType)
        val state          = Input(UInt(LinkState.width.W))
        val reclaimLCredit = Output(Bool())
    })

    require(lcreditNum <= 15)

    val queue = Module(new Queue(gen.cloneType, entries = lcreditNum, pipe = true, flow = false))

    val state         = io.state
    val enableLCredit = state === LinkState.RUN

    val lcreditsWidth   = log2Up(lcreditNum) + 1
    val lcreditInflight = RegInit(0.U(lcreditsWidth.W))
    val lcreditPool     = RegInit(lcreditNum.U(lcreditsWidth.W))
    assert(lcreditInflight + lcreditPool === lcreditNum.U)
    val lcreditOut = (lcreditPool > queue.io.count) && enableLCredit

    val ready  = lcreditInflight =/= 0.U
    val accept = ready && io.in.flitv && RegNext(io.in.flitpend)

    when(lcreditOut) {
        when(!accept) {
            lcreditInflight := lcreditInflight + 1.U
            lcreditPool     := lcreditPool - 1.U
        }
    }.otherwise {
        when(accept) {
            lcreditInflight := lcreditInflight - 1.U
            lcreditPool     := lcreditPool + 1.U
        }
    }

    queue.io.enq.valid := accept
    if (aggregateIO) {
        var lsb = 0
        queue.io.enq.bits.getElements.reverse.foreach { case e =>
            e := io.in.flit.asUInt(lsb + e.asUInt.getWidth - 1, lsb).asTypeOf(e.cloneType)
            lsb += e.asUInt.getWidth
        }
    } else {
        queue.io.enq.bits := io.in.flit
    }

    assert(!accept || queue.io.enq.ready)

    io.in.lcrdv := lcreditOut

    io.out <> queue.io.deq
    val opcodeElements = queue.io.deq.bits.elements.filter(_._1 == "opcode")
    require(opcodeElements.size == 1)
    for ((_, opcode) <- opcodeElements) {
        when(queue.io.deq.valid && opcode === 0.U) {
            // This is a *LCrdReturn flit
            queue.io.deq.ready := true.B
            io.out.valid       := false.B
        }
    }
    io.reclaimLCredit := lcreditInflight === 0.U
}

object LCredit2Decoupled {
    val defaultLCreditNum = 4

    def apply[T <: Bundle](
        left: CHIChannelIO[T],
        right: DecoupledIO[T],
        state: UInt,
        reclaimLCredit: Bool,
        suggestName: Option[String] = None,
        lcreditNum: Int = defaultLCreditNum,
        aggregateIO: Boolean = false
    ): Unit = {
        val mod = Module(new LCredit2Decoupled(right.bits.cloneType, lcreditNum, aggregateIO))
        suggestName.foreach(name => mod.suggestName(s"LCredit2Decoupled_${name}"))

        mod.io.in      <> left
        right          <> mod.io.out
        mod.io.state   := state
        reclaimLCredit := mod.io.reclaimLCredit
    }
}

class Decoupled2LCredit[T <: Bundle](gen: T, aggregateIO: Boolean = false) extends Module {
    val io = IO(new Bundle {
        val in    = Flipped(DecoupledIO(gen.cloneType))
        val out   = CHIChannelIO(gen.cloneType, aggregateIO)
        val state = Input(UInt(LinkState.width.W))
    })

    val state          = io.state
    val disableFlit    = state === LinkState.STOP || state === LinkState.ACTIVATE
    val disableLCredit = state === LinkState.STOP
    val acceptLCredit  = io.out.lcrdv && !disableLCredit

    // The maximum number of L-Credits that a receiver can provide is 15.
    val lcreditsMax = 15
    val lcreditPool = RegInit(0.U(log2Up(lcreditsMax).W))

    val returnLCreditValid = !io.in.valid && state === LinkState.DEACTIVATE && lcreditPool =/= 0.U

    when(acceptLCredit) {
        when(!io.out.flitv) {
            lcreditPool := lcreditPool + 1.U
            assert(lcreditPool + 1.U =/= 0.U, "L-Credit pool overflow")
        }
    }.otherwise {
        when(io.out.flitv) {
            lcreditPool := lcreditPool - 1.U
        }
    }

    io.in.ready     := lcreditPool =/= 0.U && !disableFlit
    io.out.flitpend := true.B
    io.out.flitv    := io.in.fire || returnLCreditValid
    if (aggregateIO) {
        io.out.flit := Mux(
            io.in.valid,
            Cat(io.in.bits.getElements.map(_.asUInt)),
            0.U // LCrdReturn
        )
    } else {
        io.out.flit := io.in.bits
    }
}

object Decoupled2LCredit {
    def apply[T <: Bundle](
        left: DecoupledIO[T],
        right: CHIChannelIO[T],
        state: UInt,
        suggestName: Option[String] = None,
        aggregateIO: Boolean = false
    ): Unit = {
        val mod = Module(new Decoupled2LCredit(left.bits.cloneType, aggregateIO))
        suggestName.foreach(name => mod.suggestName(s"Decoupled2LCredit_${name}"))

        mod.io.in    <> left
        right        <> mod.io.out
        mod.io.state := state
    }
}

class CHIBridgeOutput(implicit p: Parameters) extends L2Bundle {
    val chi         = CHIBundleDownstream(chiBundleParams)
    val chiLinkCtrl = new CHILinkCtrlIO()
}

class LinkMonitor()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle() {
        val in = new Bundle {
            val chi = Flipped(CHIBundleDecoupled(chiBundleParams))
        }
        val out = new Bundle {
            val chi         = CHIBundleDownstream(chiBundleParams)
            val chiLinkCtrl = new CHILinkCtrlIO()
        }

        val nodeID = Input(UInt(12.W))
    })

    val txState = RegInit(LinkState.STOP)
    val rxState = RegInit(LinkState.STOP)

    txState := MuxLookup(Cat(io.out.chiLinkCtrl.txactivereq, io.out.chiLinkCtrl.txactiveack), LinkState.STOP)(
        Seq(
            Cat(true.B, false.B)  -> LinkState.ACTIVATE,
            Cat(true.B, true.B)   -> LinkState.RUN,
            Cat(false.B, true.B)  -> LinkState.DEACTIVATE,
            Cat(false.B, false.B) -> LinkState.STOP
        )
    )

    rxState := MuxLookup(Cat(io.out.chiLinkCtrl.rxactivereq, io.out.chiLinkCtrl.rxactiveack), LinkState.STOP)(
        Seq(
            Cat(true.B, false.B)  -> LinkState.ACTIVATE,
            Cat(true.B, true.B)   -> LinkState.RUN,
            Cat(false.B, true.B)  -> LinkState.DEACTIVATE,
            Cat(false.B, false.B) -> LinkState.STOP
        )
    )

    val rxsnpDeact, rxrspDeact, rxdatDeact = Wire(Bool())
    val rxDeact                            = rxsnpDeact && rxrspDeact && rxdatDeact
    Decoupled2LCredit(setSrcID(io.in.chi.txreq, io.nodeID), io.out.chi.txreq, txState, Some("txreq"))
    Decoupled2LCredit(setSrcID(io.in.chi.txrsp, io.nodeID), io.out.chi.txrsp, txState, Some("txrsp"))
    Decoupled2LCredit(setSrcID(io.in.chi.txdat, io.nodeID), io.out.chi.txdat, txState, Some("txdat"))
    LCredit2Decoupled(io.out.chi.rxsnp, io.in.chi.rxsnp, rxState, rxsnpDeact, Some("rxsnp"), lcreditNum = rxsnpCreditMAX)
    LCredit2Decoupled(io.out.chi.rxrsp, io.in.chi.rxrsp, rxState, rxrspDeact, Some("rxrsp"), lcreditNum = rxrspCreditMAX)
    LCredit2Decoupled(io.out.chi.rxdat, io.in.chi.rxdat, rxState, rxdatDeact, Some("rxdat"), lcreditNum = rxdatCreditMAX)

    io.out.chiLinkCtrl.txsactive   := true.B // TODO:
    io.out.chiLinkCtrl.txactivereq := !reset.asBool
    io.out.chiLinkCtrl.rxactiveack := RegNext(io.out.chiLinkCtrl.rxactivereq) || !rxDeact

    dontTouch(io.out)

    def setSrcID[T <: Bundle](in: DecoupledIO[T], srcID: UInt = 0.U): DecoupledIO[T] = {
        val out = Wire(in.cloneType)
        out                                               <> in
        out.bits.elements.filter(_._1 == "srcID").head._2 := srcID
        out
    }
}

class LinkMonitorDecoupled()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle() {
        val in = new Bundle {
            val chi = Flipped(CHIBundleDecoupled(chiBundleParams))
        }
        val out = new Bundle {
            val chi         = CHIBundleDecoupled(chiBundleParams)
            val chiLinkCtrl = new CHILinkCtrlIO()
        }

        val nodeID = Input(UInt(12.W))
    })

    def setSrcID[T <: Bundle](in: DecoupledIO[T], srcID: UInt = 0.U): DecoupledIO[T] = {
        val out = Wire(in.cloneType)
        out                                               <> in
        out.bits.elements.filter(_._1 == "srcID").head._2 := srcID
        out
    }

    io.out.chi.txreq <> setSrcID(io.in.chi.txreq, io.nodeID)
    io.out.chi.txrsp <> setSrcID(io.in.chi.txrsp, io.nodeID)
    io.out.chi.txdat <> setSrcID(io.in.chi.txdat, io.nodeID)
    io.out.chi.rxsnp <>  io.in.chi.rxsnp
    io.out.chi.rxrsp <>  io.in.chi.rxrsp
    io.out.chi.rxdat <>  io.in.chi.rxdat
    io.out.chiLinkCtrl <> DontCare
}
