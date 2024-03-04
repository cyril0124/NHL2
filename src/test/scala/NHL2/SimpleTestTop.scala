package NHL2

import chi._
import circt.stage.{ChiselStage, FirtoolOption}
import chisel3._
import chisel3.util.{circt, _}
import chisel3.stage.ChiselGeneratorAnnotation
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._

//
// A simple chi endpoint should deal with everything. (ICN + HN + SN)
// This module is only for test.
//
class SimpleEndpointCHI()(implicit p: Parameters) extends NHL2Module {
    val io = IO(new Bundle {
        val chi = Flipped(CHIBundle(CHIBundleParameters.default()))
    })

    val fakeCHIBundle = WireInit(0.U.asTypeOf(CHIBundle(CHIBundleParameters.default())))
    io.chi <> fakeCHIBundle

    // Keep clock and reset
    val (_, cnt) = Counter(true.B, 10)
    dontTouch(cnt)

    dontTouch(io)
}

class SimpleTestTop()(implicit p: Parameters) extends LazyModule {
    val cacheParams = p(NHL2ParamKey)

    def createClientNode(name: String, sources: Int) = {
        val masterNode = TLClientNode(
            Seq(
                TLMasterPortParameters.v2(
                    masters = Seq(
                        TLMasterParameters.v1(
                            name = name,
                            sourceId = IdRange(0, sources),
                            supportsProbe = TransferSizes(cacheParams.blockBytes)
                        )
                    ),
                    channelBytes = TLChannelBeatBytes(cacheParams.blockBytes),
                    minLatency = 1,
                    echoFields = Nil,
                    requestFields = Nil, // TODO: Seq(AliasField(2)),
                    responseKeys = Nil   // TODO:
                )
            )
        )
        masterNode
    }

    val masterNode = createClientNode("L1", 16)

    val l2 = LazyModule(new NHL2())

    l2.node := masterNode

    lazy val module = new LazyModuleImp(this) {
        masterNode.makeIOs()(ValName(s"tl_in"))

        val chiEndpoint = Module(new SimpleEndpointCHI())
        
        chiEndpoint.io.chi <> l2.module.io.chi
    }
}

object SimpleTestTop extends App {
    val config = new Config((_, _, _) => { case NHL2ParamKey =>
        NHL2Param()
    })
    val top = DisableMonitors(p => LazyModule(new SimpleTestTop()(p)))(config)

    (new ChiselStage).execute(
        Array("--target", "verilog") ++ args,
        Seq(
            FirtoolOption("-O=release"),
            FirtoolOption("--disable-all-randomization"),
            FirtoolOption("--disable-annotation-unknown"),
            FirtoolOption("--strip-debug-info"),
            FirtoolOption("--lower-memories"),
            FirtoolOption(
                "--lowering-options=noAlwaysComb," +
                    " disallowPortDeclSharing, disallowLocalVariables," +
                    " emittedLineLength=120, explicitBitcast, locationInfoStyle=plain," +
                    " disallowExpressionInliningInPorts, disallowMuxInlining"
            ),
            ChiselGeneratorAnnotation(() => top.module)
        )
    )
}
