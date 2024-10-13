// @formatter:off
package SimpleL2.prefetch

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.tilelink.TLPermissions._
import SimpleL2._

// custom l2 - l1 tlb
object TlbCmd {
  def read  = "b00".U
  def write = "b01".U
  def exec  = "b10".U

  def atom_read  = "b100".U // lr
  def atom_write = "b101".U // sc / amo

  def apply() = UInt(3.W)
  def isRead(a: UInt) = a(1,0)===read
  def isWrite(a: UInt) = a(1,0)===write
  def isExec(a: UInt) = a(1,0)===exec

  def isAtom(a: UInt) = a(2)
  def isAmo(a: UInt) = a===atom_write // NOTE: sc mixed
}

// Svpbmt extension
object Pbmt {
  def pma:  UInt = "b00".U  // None
  def nc:   UInt = "b01".U  // Non-cacheable, idempotent, weakly-ordered (RVWMO), main memory
  def io:   UInt = "b10".U  // Non-cacheable, non-idempotent, strongly-ordered (I/O ordering), I/O
  def rsvd: UInt = "b11".U  // Reserved for future standard use
  def width: Int = 2
  
  def apply() = UInt(width.W)
  def isUncache(a: UInt) = a===nc || a===io
}

class TlbExceptionBundle extends Bundle {
  val ld = Output(Bool())
  val st = Output(Bool())
  val instr = Output(Bool())
}

class L2TlbReq(implicit p: Parameters) extends L2Bundle{
  val XLEN = 64
  val vaddr = Output(UInt((fullVAddrBits+offsetBits).W))
  val cmd = Output(TlbCmd())
  val isPrefetch = Output(Bool())
  val size = Output(UInt(log2Ceil(log2Ceil(XLEN/8) + 1).W))
  val kill = Output(Bool()) // Use for blocked tlb that need sync with other module like icache
  val no_translate = Output(Bool()) // do not translate, but still do pmp/pma check
}

class L2TlbResp(nDups: Int = 1)(implicit p: Parameters) extends L2Bundle {
  val paddr = Vec(nDups, Output(UInt(fullAddressBits.W)))
  val pbmt = Output(Pbmt.apply())
  val miss = Output(Bool())
  val excp = Vec(nDups, new Bundle {
    val gpf = new TlbExceptionBundle()
    val pf = new TlbExceptionBundle()
    val af = new TlbExceptionBundle()
  })
}

class PMPRespBundle(implicit p: Parameters) extends L2Bundle {
  val ld = Output(Bool())
  val st = Output(Bool())
  val instr = Output(Bool())
  val mmio = Output(Bool())
  val atomic = Output(Bool())
}

class L2ToL1TlbIO(nRespDups: Int = 1)(implicit p: Parameters) extends L2Bundle{
  val req = DecoupledIO(new L2TlbReq)
  val req_kill = Output(Bool())
  val resp = Flipped(DecoupledIO(new L2TlbResp(nRespDups)))
  val pmp_resp = Flipped(new PMPRespBundle())
}

// @formatter:on
