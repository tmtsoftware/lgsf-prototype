import sbt.Keys.version
import sbt.{AutoPlugin, Plugins, Setting}

/**
* ==================== IMPORTANT - DO NOT CHANGE =================
* This is required to display correct version for `BtoPrototypeContainerCmdApp` and `BtoPrototypeHostConfigApp`
* Basically this overrides version of ContainerCmd/HostConfig which is coming from csw-framework` with version of bto prototype
*
* */
object CswBuildInfo extends AutoPlugin {
  import sbtbuildinfo.BuildInfoPlugin
  import BuildInfoPlugin.autoImport._

  override def requires: Plugins = BuildInfoPlugin

  override def projectSettings: Seq[Setting[_]] = Seq(
    buildInfoKeys := Seq[BuildInfoKey](version),
    buildInfoPackage := "csw.services"
  )
}