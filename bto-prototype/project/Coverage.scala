import sbt.*

object Coverage extends AutoPlugin {
  import scoverage.ScoverageSbtPlugin
  import ScoverageSbtPlugin.autoImport.*

  override def requires: Plugins = ScoverageSbtPlugin

  override def projectSettings: Seq[Setting[?]] = Seq(
    coverageEnabled := true,
    coverageMinimumStmtTotal := 80,
    coverageFailOnMinimum := true,
    coverageHighlighting := true,
    coverageOutputCobertura := true,
    coverageOutputXML := true
  )

}
