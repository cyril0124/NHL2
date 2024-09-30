package SimpleL2.chi

import chisel3._
import chisel3.util._

object CHIOpcodeREQ {
    val width = 7 // TODO: issueE has opcode bits of 7

    val ReqLCrdReturn = 0x00.U(width.W)
    val ReadShared    = 0x01.U(width.W)
    val ReadClean     = 0x02.U(width.W)
    val ReadOnce      = 0x03.U(width.W)
    val ReadNoSnp     = 0x04.U(width.W)
    val PCrdReturn    = 0x05.U(width.W)

    val ReadUnique   = 0x07.U(width.W)
    val CleanShared  = 0x08.U(width.W)
    val CleanInvalid = 0x09.U(width.W)
    val MakeInvalid  = 0x0a.U(width.W)
    val CleanUnique  = 0x0b.U(width.W)
    val MakeUnique   = 0x0c.U(width.W)
    val Evict        = 0x0d.U(width.W)

    val DVMOp          = 0x14.U(width.W)
    val WriteEvictFull = 0x15.U(width.W)

    val WriteCleanFull  = 0x17.U(width.W)
    val WriteUniquePtl  = 0x18.U(width.W)
    val WriteUniqueFull = 0x19.U(width.W)
    val WriteBackPtl    = 0x1a.U(width.W)
    val WriteBackFull   = 0x1b.U(width.W)
    val WriteNoSnpPtl   = 0x1c.U(width.W)
    val WriteNoSnpFull  = 0x1d.U(width.W)

    val WriteUniqueFullStash = 0x20.U(width.W)
    val WriteUniquePtlStash  = 0x21.U(width.W)
    val StashOnceShared      = 0x22.U(width.W)
    val StashOnceUnique      = 0x23.U(width.W)
    val ReadOnceCleanInvalid = 0x24.U(width.W)
    val ReadOnceMakeInvalid  = 0x25.U(width.W)
    val ReadNotSharedDirty   = 0x26.U(width.W)
    val CleanSharedPersist   = 0x27.U(width.W)

    val AtomicStore_ADD  = 0x28.U(width.W)
    val AtomicStore_CLR  = 0x29.U(width.W)
    val AtomicStore_EOR  = 0x2a.U(width.W)
    val AtomicStore_SET  = 0x2b.U(width.W)
    val AtomicStore_SMAX = 0x2c.U(width.W)
    val AtomicStore_SMIN = 0x2d.U(width.W)
    val AtomicStore_UMAX = 0x2e.U(width.W)
    val AtomicStore_UMIN = 0x2f.U(width.W)
    val AtomicLoad_ADD   = 0x30.U(width.W)
    val AtomicLoad_CLR   = 0x31.U(width.W)
    val AtomicLoad_EOR   = 0x32.U(width.W)
    val AtomicLoad_SET   = 0x33.U(width.W)
    val AtomicLoad_SMAX  = 0x34.U(width.W)
    val AtomicLoad_SMIN  = 0x35.U(width.W)
    val AtomicLoad_UMAX  = 0x36.U(width.W)
    val AtomicLoad_UMIN  = 0x37.U(width.W)
    val AtomicSwap       = 0x38.U(width.W)
    val AtomicCompare    = 0x39.U(width.W)
    val PrefetchTgt      = 0x3a.U(width.W)
}

object CHIOpcodeRSP {
    val width = 5

    val RespLCrdReturn = 0x0.U(width.W)
    val SnpResp        = 0x1.U(width.W)
    val CompAck        = 0x2.U(width.W)
    val RetryAck       = 0x3.U(width.W)
    val Comp           = 0x4.U(width.W)
    val CompDBIDResp   = 0x5.U(width.W)
    val DBIDResp       = 0x6.U(width.W)
    val PCrdGrant      = 0x7.U(width.W)
    val ReadReceipt    = 0x8.U(width.W)
    val SnpRespFwded   = 0x9.U(width.W)
}

object CHIOpcodeSNP {
    val width = 5

    val SnpLCrdReturn       = 0x00.U(width.W)
    val SnpShared           = 0x01.U(width.W)
    val SnpClean            = 0x02.U(width.W)
    val SnpOnce             = 0x03.U(width.W)
    val SnpNotSharedDirty   = 0x04.U(width.W)
    val SnpUniqueStash      = 0x05.U(width.W)
    val SnpMakeInvalidStash = 0x06.U(width.W)
    val SnpUnique           = 0x07.U(width.W)
    val SnpCleanShared      = 0x08.U(width.W)
    val SnpCleanInvalid     = 0x09.U(width.W)
    val SnpMakeInvalid      = 0x0a.U(width.W)
    val SnpStashUnique      = 0x0b.U(width.W)
    val SnpStashShared      = 0x0c.U(width.W)
    val SnpDVMOp            = 0x0d.U(width.W)

    val SnpSharedFwd         = 0x11.U(width.W)
    val SnpCleanFwd          = 0x12.U(width.W)
    val SnpOnceFwd           = 0x13.U(width.W)
    val SnpNotSharedDirtyFwd = 0x14.U(width.W)

    val SnpUniqueFwd = 0x17.U(width.W)

    def widthCheck(opcode: UInt): Unit = { require(opcode.getWidth >= width) }

    def isSnpXStash(opcode: UInt): Bool = {
        widthCheck(opcode)
        opcode === SnpUniqueStash || opcode === SnpMakeInvalidStash
    }

    def isSnpStashX(opcode: UInt): Bool = {
        widthCheck(opcode)
        opcode === SnpStashUnique || opcode === SnpStashShared
    }

    def isSnpXFwd(opcode: UInt): Bool = {
        widthCheck(opcode)
        opcode >= SnpSharedFwd
    }

    def isSnpOnceX(opcode: UInt): Bool = {
        widthCheck(opcode)
        opcode === SnpOnce || opcode === SnpOnceFwd
    }

    def isSnpCleanX(opcode: UInt): Bool = {
        widthCheck(opcode)
        opcode === SnpClean || opcode === SnpCleanFwd
    }

    def isSnpSharedX(opcode: UInt): Bool = {
        widthCheck(opcode)
        opcode === SnpShared || opcode === SnpSharedFwd
    }

    def isSnpNotSharedDirtyX(opcode: UInt): Bool = {
        widthCheck(opcode)
        opcode === SnpNotSharedDirty || opcode === SnpNotSharedDirtyFwd
    }

    def isSnpToB(opcode: UInt): Bool = {
        isSnpCleanX(opcode) || isSnpSharedX(opcode) || isSnpNotSharedDirtyX(opcode)
    }

    def isSnpToN(opcode: UInt): Bool = {
        isSnpUniqueX(opcode) || opcode === SnpCleanInvalid || isSnpMakeInvalidX(opcode)
    }

    def isSnpCleanShared(opcode: UInt): Bool = {
        widthCheck(opcode)
        opcode === SnpCleanShared
    }

    def isSnpToBNonFwd(opcode: UInt): Bool = {
        widthCheck(opcode)
        opcode === SnpClean ||
        opcode === SnpNotSharedDirty ||
        opcode === SnpShared
    }

    def isSnpToBFwd(opcode: UInt): Bool = {
        widthCheck(opcode)
        opcode === SnpCleanFwd ||
        opcode === SnpNotSharedDirtyFwd ||
        opcode === SnpSharedFwd
    }

    def isSnpToNNonFwd(opcode: UInt): Bool = {
        widthCheck(opcode)
        opcode === SnpUnique || opcode === SnpUniqueStash
    }

    def isSnpToNFwd(opcode: UInt): Bool = {
        widthCheck(opcode)
        opcode === SnpUniqueFwd
    }

    def isSnpUniqueX(opcode: UInt): Bool = {
        widthCheck(opcode)
        opcode === SnpUnique || opcode === SnpUniqueFwd || opcode === SnpUniqueStash
    }

    def isSnpMakeInvalidX(opcode: UInt): Bool = {
        widthCheck(opcode)
        opcode === SnpMakeInvalid || opcode === SnpMakeInvalidStash
    }
}

object CHIOpcodeDAT {
    val width = 4

    val DataLCrdReturn           = 0x0.U(width.W)
    val SnpRespData              = 0x1.U(width.W)
    val CopyBackWrData           = 0x2.U(width.W)
    val NonCopyBackWrData        = 0x3.U(width.W)
    val CompData                 = 0x4.U(width.W)
    val SnpRespDataPtl           = 0x5.U(width.W)
    val SnpRespDataFwded         = 0x6.U(width.W)
    val WriteDataCancel          = 0x7.U(width.W)
    val NonCopyBackWrDataCompAck = 0xc.U(width.W)

    def widthCheck(opcode: UInt): Unit = { require(opcode.getWidth >= width) }
    def isSnpRespDataX(opcode: UInt): Bool = {
        widthCheck(opcode)
        opcode === SnpRespData || opcode === SnpRespDataPtl || opcode === SnpRespDataFwded
    }
}
