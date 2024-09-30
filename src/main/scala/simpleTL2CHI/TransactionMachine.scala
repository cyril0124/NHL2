package simpleTL2CHI

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tilelink.TLMessages._
import Utils.LeakChecker
import SimpleL2.chi._
import SimpleL2.chi.CHIOpcodeREQ._
import SimpleL2.chi.CHIOpcodeDAT._
import SimpleL2.chi.CHIOpcodeRSP._

object MachineState {
    val width = 4

    val IDLE         = 0.U(width.W)
    val SEND_REQ     = 1.U(width.W) // ReadNoSnp, WriteNoSnpPtl, WriteSnpFull
    val RECV_RSP     = 2.U(width.W) // CompDBIDResp
    val SEND_DAT     = 3.U(width.W) // NonCopyBackWrData
    val RECV_RECEIPT = 4.U(width.W) // ReadReceipt
    val RECV_DAT     = 5.U(width.W) // CompData
    val RETURN_DATA  = 6.U(width.W) // Return data to TL(AccessAckData)
    val RETURN_ACK   = 7.U(width.W) // Return ack to TL(AccessAck)
}

class MachineStatus(implicit p: Parameters) extends SimpleTL2CHIBundle {
    val state   = Output(UInt(MachineState.width.W))
    val address = Output(UInt(addressBits.W))
    // val blockRead  = Output(Bool())
    // val blockWrite = Output(Bool())
}

class TransactionMachine(implicit p: Parameters) extends SimpleTL2CHIModule {
    val io = IO(new Bundle {
        val id     = Input(UInt(log2Ceil(nrMachine).W))
        val alloc  = Flipped(ValidIO(new TaskBundle))
        val status = Output(new MachineStatus)
        val tasks = new Bundle {
            val d     = Decoupled(new TLBundleD(tlBundleParams))
            val txreq = Decoupled(new CHIBundleREQ(chiBundleParams))
            val txdat = Decoupled(new CHIBundleDAT(chiBundleParams))
        }
        val resps = new Bundle {
            val rxrsp = Flipped(Decoupled(new CHIBundleRSP(chiBundleParams)))
            val rxdat = Flipped(Decoupled(new CHIBundleDAT(chiBundleParams)))
        }
    })

    io <> DontCare

    val state      = RegInit(MachineState.IDLE)
    val nextState  = WireInit(MachineState.IDLE)
    val task       = RegInit(0.U.asTypeOf(new TaskBundle))
    val rspDBID    = RegInit(0.U(chiBundleParams.dbIdBits.W))
    val rspSrcID   = RegInit(0.U(chiBundleParams.nodeIdBits.W))
    val datHomeNID = RegInit(0.U(chiBundleParams.nodeIdBits.W)) // TODO: used when CompAck is required
    val rspGetComp = RegInit(false.B)
    val rspGetDBID = RegInit(false.B)

    when(io.alloc.fire) {
        task := io.alloc.bits

        rspGetComp := false.B
        rspGetDBID := false.B
    }

    nextState := state
    switch(state) {
        is(MachineState.IDLE) {
            when(io.alloc.fire) {
                nextState := MachineState.SEND_REQ
            }

            assert(!io.tasks.txreq.fire)
            assert(!io.resps.rxdat.fire)
            assert(!io.resps.rxrsp.fire)
        }

        is(MachineState.SEND_REQ) {
            when(io.tasks.txreq.fire) {
                when(task.opcode === Get) {
                    // nextState := MachineState.RECV_DAT
                    nextState := MachineState.RECV_RECEIPT
                }.otherwise {
                    nextState := MachineState.RECV_RSP
                    assert(task.opcode === PutFullData || task.opcode === PutPartialData)
                }
            }
        }

        is(MachineState.RECV_RECEIPT) {
            val rxrsp = io.resps.rxrsp
            when(rxrsp.fire && rxrsp.bits.opcode === ReadReceipt) {
                nextState := MachineState.RECV_DAT
            }
        }

        is(MachineState.RECV_DAT) {
            when(io.resps.rxdat.fire) {
                nextState  := MachineState.RETURN_DATA
                task.data  := io.resps.rxdat.bits.data
                datHomeNID := io.resps.rxdat.bits.homeNID
            }
        }

        is(MachineState.RECV_RSP) {
            val rxrsp         = io.resps.rxrsp
            val rspIsComp     = rxrsp.bits.opcode === Comp
            val rspIsDBID     = rxrsp.bits.opcode === DBIDResp
            val rspIsCompDBID = rxrsp.bits.opcode === CompDBIDResp

            // Transaction combination: Comp + DBIDResp, DBIDResp + Comp, CompDBIDResp
            when(rxrsp.fire && (rspIsComp || rspIsDBID || rspIsCompDBID)) {
                assert(io.resps.rxrsp.bits.respErr === RespErr.NormalOkay, "TODO: handle error")

                rspGetComp := rspGetComp || rspIsComp
                rspGetDBID := rspGetDBID || rspIsDBID

                when(rspIsDBID || rspIsCompDBID) {
                    rspDBID  := io.resps.rxrsp.bits.dbID
                    rspSrcID := io.resps.rxrsp.bits.srcID
                }

                when(rspIsCompDBID || (rspIsComp && rspGetDBID) || (rspIsDBID && rspGetComp)) {
                    nextState := MachineState.SEND_DAT
                }
            }
        }

        is(MachineState.SEND_DAT) {
            when(io.tasks.txdat.fire) {
                nextState := MachineState.RETURN_ACK
            }
        }

        is(MachineState.RETURN_DATA) {
            when(io.tasks.d.fire) {
                nextState := MachineState.IDLE
            }
        }

        is(MachineState.RETURN_ACK) {
            when(io.tasks.d.fire) {
                nextState := MachineState.IDLE
            }
        }
    }
    state := nextState

    val txreq = io.tasks.txreq
    txreq.valid           := state === MachineState.SEND_REQ
    txreq.bits            := DontCare
    txreq.bits.addr       := task.address
    txreq.bits.opcode     := Mux(task.opcode === Get, ReadNoSnp, WriteNoSnpPtl /* TODO: WriteNoSnpFull ? */ )
    txreq.bits.txnID      := io.id
    txreq.bits.allowRetry := false.B // TODO: Retry
    txreq.bits.expCompAck := Mux(task.opcode === Get, false.B, true.B /* OWO ordering require CompAck */ )
    txreq.bits.memAttr    := MemAttr(allocate = false.B, cacheable = false.B, device = true.B, ewa = false.B /* EAW can take any value for ReadNoSnp/WriteNoSnp* */ )
    txreq.bits.size       := 3.U     // 2^3 = 8 bytes
    txreq.bits.order      := Mux(task.opcode === Get, Order.RequestOrder, Order.OWO)

    val txdat = io.tasks.txdat
    txdat.valid       := state === MachineState.SEND_DAT
    txdat.bits        := DontCare
    txdat.bits.dbID   := rspDBID
    txdat.bits.tgtID  := rspSrcID
    txdat.bits.be     := Mux(task.opcode === PutFullData, Fill(8, 1.U), task.mask)
    txdat.bits.data   := task.data
    txdat.bits.opcode := NonCopyBackWrDataCompAck
    txdat.bits.resp   := 0.U
    txdat.bits.txnID  := io.id

    val d = io.tasks.d
    d.valid        := state === MachineState.RETURN_DATA || state === MachineState.RETURN_ACK
    d.bits         := DontCare
    d.bits.data    := Mux(state === MachineState.RETURN_DATA, task.data, 0.U)
    d.bits.corrupt := false.B
    d.bits.opcode  := Mux(state === MachineState.RETURN_DATA, AccessAckData, AccessAck)
    d.bits.param   := DontCare
    d.bits.sink    := io.id
    d.bits.source  := task.source
    d.bits.size    := 3.U // 2^3 = 8 bytes

    io.status.state   := state
    io.status.address := task.address
    // io.status.blockRead  := state <= MachineState.RECV_RECEIPT
    // io.status.blockWrite := state <= MachineState.RETURN_ACK

    io.resps.rxrsp.ready := true.B
    io.resps.rxdat.ready := true.B

    LeakChecker(io.status.state =/= MachineState.IDLE, io.status.state === MachineState.IDLE, Some("machine_valid"), deadlockThreshold)

    dontTouch(io)
}
