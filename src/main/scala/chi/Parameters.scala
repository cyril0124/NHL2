package chi

import chisel3._
import chisel3.util._

case class CHIBundleParameters(
    nodeIDWidth: Int,
    reqAddrWidth: Int,
    snpAddrWidth: Int,
    dataWidth: Int,
    enableDataCheck: Boolean
) {
    require(nodeIDWidth >= 7 && nodeIDWidth <= 11)
    require(reqAddrWidth >= 44 && reqAddrWidth <= 52)
    require(snpAddrWidth >= 41 && snpAddrWidth <= 49)
    require(dataWidth == 128 || dataWidth == 256 || dataWidth == 512)
}

object CHIBundleParameters {
    def default(): CHIBundleParameters = {
        CHIBundleParameters(
            nodeIDWidth = 11,
            reqAddrWidth = 52,
            snpAddrWidth = 49,
            dataWidth = 256,
            enableDataCheck = false
        )
    }
}
