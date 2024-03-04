package chi

import chisel3._
import chisel3.util._

case class CHIBundleParameters(
    tgtIDWidth: Int,
    srcIDWidth: Int,
    reqAddrWidth: Int,
    snpAddrWidth: Int,
    homeNIDWidth: Int,
    dataWidth: Int,
    enableDataCheck: Boolean
) {
    require(tgtIDWidth >= 7 && tgtIDWidth <= 11)
    require(srcIDWidth >= 7 && srcIDWidth <= 11)
    require(reqAddrWidth >= 44 && reqAddrWidth <= 52)
    require(snpAddrWidth >= 41 && snpAddrWidth <= 49)
    require(homeNIDWidth >= 7 && homeNIDWidth <= 11)
    require(dataWidth == 128 || dataWidth == 256 || dataWidth == 512)
}

object CHIBundleParameters {
    def default(): CHIBundleParameters = {
        CHIBundleParameters(
            tgtIDWidth = 11,
            srcIDWidth = 11,
            reqAddrWidth = 52,
            snpAddrWidth = 49,
            homeNIDWidth = 11,
            dataWidth = 256,
            enableDataCheck = false
        )
    }
}
