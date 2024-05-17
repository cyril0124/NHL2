package SimpleL2.Configs

import SimpleL2.Bundles.CHIBundleParameters
import freechips.rocketchip.tilelink._

object L2CacheConfig {
    // @formatter:off
    val tlBundleParams = TLBundleParameters(
        addressBits = 44,
        dataBits = 256,
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
        addressBits = 44,
        dataBits = 256,
        dataCheck = false
    )
    // @formatter:on

    val nrRequestBufferEntry = 4

    val rxrspCreditMAX = 4
    val rxsnpCreditMAX = 4
    val rxdatCreditMAX = 2
}
