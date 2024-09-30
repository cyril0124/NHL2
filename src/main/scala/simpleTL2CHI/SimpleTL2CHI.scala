package simpleTL2CHI

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import Utils.GenerateVerilog
import SimpleL2.chi._
import freechips.rocketchip.rocket.CSRs.misa

abstract class SimpleTL2CHIModule(implicit val p: Parameters) extends Module with HasSimpleTL2CHIParameters
abstract class SimpleTL2CHIBundle(implicit val p: Parameters) extends Bundle with HasSimpleTL2CHIParameters

class SimpleTL2CHI()(implicit p: Parameters) extends LazyModule with HasSimpleTL2CHIParameters {
    val blockBytes = 8
    val beatBytes  = blockBytes
    val access     = TransferSizes(1, blockBytes)

    val addressRange = Seq(AddressSet(0x0, 0x7fffffff))

    val managerParameters = TLSlavePortParameters.v1(
        managers = Seq(
            TLSlaveParameters.v1(
                address = addressRange,
                regionType = RegionType.UNCACHED,
                supportsGet = access,
                supportsPutFull = access,
                supportsPutPartial = access
            )
        ),
        beatBytes = beatBytes
    )

    val node = TLManagerNode(Seq(managerParameters))

    lazy val module = new Impl

    class Impl extends LazyModuleImp(this) {
        val io = IO(new Bundle {
            val chi = new Bundle {
                val txreq = DecoupledIO(new CHIBundleREQ(chiBundleParams))
                val txdat = DecoupledIO(new CHIBundleDAT(chiBundleParams))
                val rxdat = Flipped(DecoupledIO(new CHIBundleDAT(chiBundleParams)))
                val rxrsp = Flipped(DecoupledIO(new CHIBundleRSP(chiBundleParams)))
            }
        })
        io.chi.elements.foreach(e => e._2 <> DontCare)

        val (bundleIn, edgeIn) = node.in.head

        val reqArb         = Module(new RequestArbiter)
        val machineHandler = Module(new MachineHandler)

        machineHandler.io <> DontCare

        bundleIn.d                 <> machineHandler.io.d
        reqArb.io.a                <> bundleIn.a
        reqArb.io.machineStatus    := machineHandler.io.status
        machineHandler.io.alloc_s1 <> reqArb.io.alloc_s1
        machineHandler.io.txreq    <> io.chi.txreq
        machineHandler.io.txdat    <> io.chi.txdat
        machineHandler.io.rxrsp    <> io.chi.rxrsp
        machineHandler.io.rxdat    <> io.chi.rxdat
    }
}

class SimpleTL2CHIWrapper()(implicit p: Parameters) extends LazyModule {
    val sourceMax = 16
    val masterNode = TLClientNode(
        Seq(
            TLMasterPortParameters.v1(
                clients = Seq(
                    TLMasterParameters.v1(
                        name = name,
                        sourceId = IdRange(0, sourceMax)
                    )
                )
            )
        )
    )

    val simpleTL2CHI = LazyModule(new SimpleTL2CHI()(p))
    simpleTL2CHI.node := masterNode

    lazy val module = new LazyModuleImp(this) {
        val io = IO(simpleTL2CHI.module.io.cloneType)
        masterNode.makeIOs()(ValName("tl2chi"))

        io.chi <> simpleTL2CHI.module.io.chi
    }
}

object SimpleTL2CHI extends App {
    val config = new Config((_, _, _) => { case SimpleTL2CHIParamKey =>
        SimpleTL2CHIParam()
    })

    val top = DisableMonitors(p => LazyModule(new SimpleTL2CHIWrapper()(p)))(config)

    GenerateVerilog(args, () => top.module, release = false, name = "SimpleTL2CHI", split = false)
}
