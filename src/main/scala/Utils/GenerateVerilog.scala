package Utils

import circt.stage.{ChiselStage, FirtoolOption}
import chisel3._
import chisel3.util._
import chisel3.stage.ChiselGeneratorAnnotation

object GenerateVerilog {
    def apply(args: Array[String], gen: () => RawModule) {
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
                ChiselGeneratorAnnotation(gen)
            )
        )
    }
}
