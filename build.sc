import mill._
import mill.bsp._
import scalalib._
import scalafmt._
import os.Path
import publish._
import $file.`rocket-chip`.common
import $file.`rocket-chip`.cde.common
import $file.`rocket-chip`.hardfloat.build

val defaultVersions = Map(
    "chisel" -> "6.1.0",
    "chisel-plugin" -> "6.1.0",
    "chiseltest" -> "5.0.0",
    "scala" -> "2.13.10",
    "scalatest" -> "3.2.7"
)

def getVersion(dep: String, org: String = "org.chipsalliance", cross: Boolean = false) = {
    val version = sys.env.getOrElse(dep + "Version", defaultVersions(dep))
    if (cross)
        ivy"$org:::$dep:$version"
    else
        ivy"$org::$dep:$version"
}

trait CommonModule extends ScalaModule {
    override def scalaVersion = defaultVersions("scala")
    override def scalacPluginIvyDeps = Agg(getVersion("chisel-plugin", cross = true))
    override def scalacOptions = super.scalacOptions() ++ Agg("-Ymacro-annotations", "-Ytasty-reader")
}


// 
// rocket-chip
// 
object rocketchip extends RocketChip

trait RocketChip
    extends millbuild.`rocket-chip`.common.RocketChipModule
        with SbtModule {
    
    def scalaVersion: T[String] = T(defaultVersions("scala"))

    override def millSourcePath = os.pwd / "rocket-chip"
    def chiselModule = None
    def chiselPluginJar = None
    def chiselIvy = Some(getVersion("chisel"))
    def chiselPluginIvy = Some(getVersion("chisel-plugin", cross=true))
    def macrosModule = macros
    def hardfloatModule = hardfloat
    def cdeModule = cde
    def mainargsIvy = ivy"com.lihaoyi::mainargs:0.5.0"
    def json4sJacksonIvy = ivy"org.json4s::json4s-jackson:4.0.5"


	// 
	// macros
	// 
    object macros extends Macros

    trait Macros
        extends millbuild.`rocket-chip`.common.MacrosModule
            with SbtModule {
        def scalaVersion: T[String] = T(defaultVersions("scala"))
        def scalaReflectIvy = ivy"org.scala-lang:scala-reflect:${defaultVersions("scala")}"
    }


	// 
	// hardfloat
	// 
    object hardfloat extends Hardfloat

    trait Hardfloat
        extends millbuild.`rocket-chip`.hardfloat.common.HardfloatModule {
        def scalaVersion: T[String] = T(defaultVersions("scala"))
        override def millSourcePath = os.pwd / "rocket-chip" / "hardfloat" / "hardfloat"
        def chiselModule = None
        def chiselPluginJar = None
        def chiselIvy = Some(getVersion("chisel"))
        def chiselPluginIvy = Some(getVersion("chisel-plugin", cross=true))
    }


	// 
	// CDE
	// 
    object cde extends CDE

    trait CDE
        extends millbuild.`rocket-chip`.cde.common.CDEModule
            with ScalaModule {
        def scalaVersion: T[String] = T(defaultVersions("scala"))
        override def millSourcePath = os.pwd / "rocket-chip" / "cde" / "cde"
    }
}


// 
// xs-utils
// 
object xsutils extends SbtModule with ScalafmtModule with CommonModule {
  override def ivyDeps = Agg(getVersion("chisel"))
  override def millSourcePath = os.pwd / "xs-utils"
  override def moduleDeps = super.moduleDeps ++ Seq(
    rocketchip
  )
}


// 
// utility
// 
object utility extends SbtModule with ScalafmtModule with CommonModule {
  override def millSourcePath = os.pwd / "extern" / "utility"

  override def moduleDeps = super.moduleDeps ++ Seq(rocketchip)
}


// 
// huancun
// 
object huancun extends SbtModule with ScalafmtModule with CommonModule {
  override def millSourcePath = os.pwd / "extern" / "huancun"

  override def moduleDeps = super.moduleDeps ++ Seq(rocketchip, utility)
}


// 
// coupledL2
// 
object coupledL2 extends SbtModule with ScalafmtModule with CommonModule {
  override def millSourcePath = os.pwd / "extern" / "coupledL2"

  override def moduleDeps = super.moduleDeps ++ Seq(rocketchip, utility, huancun)
}


// 
// NHL2
// 
object NHL2Project extends SbtModule with ScalafmtModule with CommonModule {
	override def millSourcePath = os.pwd
	override def ivyDeps = super.ivyDeps() ++ Agg(
		getVersion("chisel"),
		getVersion("chiseltest", "edu.berkeley.cs"),
	)
	override def moduleDeps = super.moduleDeps ++ Seq(rocketchip, xsutils, utility, coupledL2, huancun)

	object test extends SbtModuleTests with ScalafmtModule with TestModule.ScalaTest {
		override def ivyDeps = super.ivyDeps() ++ Agg(
			getVersion("scalatest","org.scalatest")
		)
		def testFramework = "org.scalatest.tools.Framework"
	}
}
