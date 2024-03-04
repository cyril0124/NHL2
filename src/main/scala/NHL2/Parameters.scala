package NHL2

import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.{Parameters, Field}

trait HasNHL2Params {
    val p: Parameters
    val cacheParams = p(NHL2ParamKey)

    lazy val edgeIn = p(EdgeInKey)
}

case object NHL2ParamKey extends Field[NHL2Param](NHL2Param())

case class NHL2Param(
    name: String = "L2",
    blockBytes: Int = 64,
    tlChannelBytes: TLChannelBeatBytes = TLChannelBeatBytes(32),
    beatBytes: Int = 32
) {}

case object EdgeInKey extends Field[TLEdgeIn]
