package NHL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

abstract class NHL2Module(implicit val p: Parameters) extends Module with HasNHL2Params
abstract class NHL2Bundle(implicit val p: Parameters) extends Bundle with HasNHL2Params
