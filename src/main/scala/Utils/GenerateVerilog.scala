package Utils

import _root_.circt.stage.{ChiselStage, FirtoolOption}
import chisel3._
import chisel3.util._
import chisel3.stage.ChiselGeneratorAnnotation

object GenerateVerilog {
    def apply(args: Array[String], gen: () => RawModule, release: Boolean = false, name: String = "Unknown", split: Boolean = false) {

        val GEN_RELEASE = sys.env.get("GEN_RELEASE")
        val isRelease = if (GEN_RELEASE.isEmpty) {
            release
        } else {
            val _isRelease = GEN_RELEASE.get == "1"
            println(s"[GenerateVerilog][${name}] get GEN_RELEASE => ${GEN_RELEASE.get} isRelease => ${_isRelease}")
            _isRelease
        }

        val SPLIT_VERILOG = sys.env.get("SPLIT_VERILOG")
        val isSplit = if (SPLIT_VERILOG.isEmpty) {
            split
        } else {
            val _isSplit = SPLIT_VERILOG.get == "1"
            println(s"[GenerateVerilog][${name}] get SPLIT_VERILOG => ${SPLIT_VERILOG.get} isSplit => ${_isSplit}")
            _isSplit
        }

        var extraFirtoolOptions = Seq(FirtoolOption("--export-module-hierarchy"))
        if (isSplit) {
            extraFirtoolOptions = extraFirtoolOptions ++ Seq(FirtoolOption("--split-verilog"), FirtoolOption("-o=./build/" + name))
        }

        val buildOpt = if (isRelease) {
            FirtoolOption("-O=release")
        } else {
            FirtoolOption("-O=debug")
        }

        (new ChiselStage).execute(
            Array("--target", "verilog") ++ args,
            Seq(
                buildOpt,
                FirtoolOption("--disable-all-randomization"),
                FirtoolOption("--disable-annotation-unknown"),
                FirtoolOption("--strip-debug-info"),
                FirtoolOption("--lower-memories"),
                FirtoolOption(
                    "--lowering-options=noAlwaysComb," +
                        " disallowPortDeclSharing, disallowLocalVariables," +
                        " emittedLineLength=120, explicitBitcast, locationInfoStyle=plain," +
                        " disallowExpressionInliningInPorts, disallowMuxInlining"
                )
            ) ++ extraFirtoolOptions ++ Seq(ChiselGeneratorAnnotation(gen))
        )
    }
}

object MultiDontTouch {
    def apply[T <: Data](signals: T*): Unit = {
        signals.foreach(s => dontTouch(s))
    }
}
