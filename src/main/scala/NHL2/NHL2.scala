package NHL2

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tilelink.TLMessages._
import org.chipsalliance.cde.config.Parameters
import scala.math.BigInt

import chi._

class NHL2(parentName: String = "L2_")(implicit p: Parameters) extends LazyModule with HasNHL2Params {

    val xfer = TransferSizes(cacheParams.blockBytes, cacheParams.blockBytes)

    val node = TLManagerNode(
        Seq(
            TLSlavePortParameters.v1(
                Seq(
                    TLSlaveParameters.v1(
                        address = Seq(
                            AddressSet(0, BigInt("ffffffffffff", 16))
                        ), // TODO: This should be passed from lower-level memory(i.e. underlying AXI-RAM)
                        regionType = RegionType.CACHED,
                        supportsAcquireT = xfer,
                        supportsAcquireB = xfer,
                        fifoId = None
                    )
                ), // requests are handled in order
                beatBytes = cacheParams.beatBytes,
                endSinkId = 32 // TODO: Pamameterize
            )
        )
    )

    lazy val module = new Impl
    class Impl extends LazyModuleImp(this) {
        //
        // Evaluate final parameters
        //
        val bundleIn = node.in.head._1 // TileLink input bundle (solid io signals)
        val edgeIn   = node.in.head._2 // TileLink input edge parameters
        val finalParameters: Parameters = p.alterPartial { case EdgeInKey =>
            edgeIn
        }

        //
        // IO declaration
        //
        val io = IO(new Bundle {
            val test = Output(Bool())
            val chi  = CHIBundle(CHIBundleParameters.default())
        })

        // Keep clock and reset
        val (_, cnt) = Counter(true.B, 10)
        io.test := cnt > 5.U
        dontTouch(cnt)

        // Keep bundleIn exist
        dontTouch(bundleIn)

        // Keep chi output bundle exist
        val fakeCHIBundle = WireInit(0.U.asTypeOf(CHIBundle(CHIBundleParameters.default())))
        io.chi <> fakeCHIBundle

        dontTouch(io)

        //
        // TODO: Other modules
        //
        // val slice = Module(new Slice()(finalParameters))
        // slice.io.in <> bundleIn
    }
}
