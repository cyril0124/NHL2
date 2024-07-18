package Utils

import chisel3._
import chisel3.util._
import chisel3.experimental.{SourceInfo, SourceLine}

object LeakChecker {
    def apply(incCounter: Bool, resetCounter: Bool, name: Option[String] = None, maxCount: Int = 1000, customWarning: Option[String] = None)(implicit s: SourceInfo) = {
        val cntName = name.getOrElse("Unknown") + "_leak_cnt"
        val cnt     = RegInit(0.U(64.W)).suggestName(cntName)

        when(resetCounter) {
            cnt := 0.U
        }.elsewhen(incCounter) {
            cnt := cnt + 1.U
        }

        val debugInfo = s match {
            case SourceLine(filename, line, col) => s"($filename:$line:$col)"
            case _                               => ""
        }
        assert(cnt <= maxCount.U, s"${cntName} > ${maxCount}! " + customWarning.getOrElse("Leak detected on " + cntName) + " at " + debugInfo)
    }

    def withCallback(incCounter: Bool, resetCounter: Bool, name: Option[String] = None, maxCount: Int = 1000, customWarning: Option[String] = None)(callback: => Any = {})(implicit s: SourceInfo) = {
        val cntName = name.getOrElse("Unknown") + "_leak_cnt"
        val cnt     = RegInit(0.U(64.W)).suggestName(cntName)

        when(resetCounter) {
            cnt := 0.U
        }.elsewhen(incCounter) {
            cnt := cnt + 1.U
        }

        val debugInfo = s match {
            case SourceLine(filename, line, col) => s"($filename:$line:$col)"
            case _                               => ""
        }
        when(cnt > maxCount.U) {
            callback
            assert(cnt <= maxCount.U, s"${cntName} > ${maxCount}! " + customWarning.getOrElse("Leak detected on " + cntName) + " at " + debugInfo)
        }
    }
}
