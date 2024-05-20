package SimpleL2.Configs

import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config._
import SimpleL2.Bundles.CHIBundleParameters

case object L2ParamKey extends Field[L2Param](L2Param())

case class L2Param(
    ways: Int = 8,
    sets: Int = 256,
    blockBytes: Int = 64,
    dataBits: Int = 64 * 8, // 64 Byte
    addressBits: Int = 44,
    enableClockGate: Boolean = true,
    nrMSHR: Int = 16,
    nrTmpDataEntry: Int = 16,
    nrRequestBufferEntry: Int = 4,
    rxrspCreditMAX: Int = 4,
    rxsnpCreditMAX: Int = 4,
    rxdatCreditMAX: Int = 2
) {
    require(dataBits == 64 * 8)
    require(nrMSHR == nrTmpDataEntry)
}

trait HasL2Param {
    val p: Parameters
    val l2param = p(L2ParamKey)

    val ways        = l2param.ways
    val sets        = l2param.sets
    val addressBits = l2param.addressBits
    val dataBits    = l2param.dataBits
    val setBits     = log2Ceil(l2param.sets)
    val offsetBits  = log2Ceil(l2param.blockBytes)
    val tagBits     = l2param.addressBits - setBits - offsetBits

    val enableClockGate      = l2param.enableClockGate
    val nrTmpDataEntry       = l2param.nrTmpDataEntry
    val nrRequestBufferEntry = l2param.nrRequestBufferEntry

    val rxrspCreditMAX = l2param.rxrspCreditMAX
    val rxsnpCreditMAX = l2param.rxsnpCreditMAX
    val rxdatCreditMAX = l2param.rxdatCreditMAX

    // @formatter:off
    val tlBundleParams = TLBundleParameters(
        addressBits = addressBits,
        dataBits = dataBits,
        sourceBits = 7,
        sinkBits = 7,
        sizeBits = 3,
        echoFields = Nil,
        requestFields = Nil,
        responseFields = Nil,
        hasBCE = true
    )
    // @formatter:on

    // @formatter:off
    val chiBundleParams = CHIBundleParameters(
        nodeIdBits = 7,
        addressBits = addressBits,
        dataBits = dataBits,
        dataCheck = false
    )
    // @formatter:on
}

// object L2CacheConfig {
//     val addressBits = 44
//     val dataBits    = 256

//     val sets       = 256
//     val ways       = 8
//     val blockBytes = 64

//     val setBits    = log2Ceil(sets)
//     val offsetBits = log2Ceil(blockBytes)
//     val tagBits    = addressBits - setBits - offsetBits

//     val enableClockGate = true

//     val nrMSHR         = 16
//     val nrTmpDataEntry = 16

//     val nrRequestBufferEntry = 4

//     val rxrspCreditMAX = 4
//     val rxsnpCreditMAX = 4
//     val rxdatCreditMAX = 2

//     // @formatter:off
//     val tlBundleParams = TLBundleParameters(
//         addressBits = addressBits,
//         dataBits = dataBits,
//         sourceBits = 7,
//         sinkBits = 7,
//         sizeBits = 3,
//         echoFields = Nil,
//         requestFields = Nil,
//         responseFields = Nil,
//         hasBCE = true
//     )
//     // @formatter:on

//     // @formatter:off
//     val chiBundleParams = CHIBundleParameters(
//         nodeIdBits = 7,
//         addressBits = addressBits,
//         dataBits = dataBits,
//         dataCheck = false
//     )
//     // @formatter:on
// }
