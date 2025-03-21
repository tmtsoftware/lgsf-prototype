import java.io.FileReader
import java.util.Properties

import sbt._

import scala.util.control.NonFatal

object Libs {
  val ScalaVersion = "2.13.8"

  val `scalatest`   = "org.scalatest"          %% "scalatest"   % "3.2.11"    //Apache License 2.0
  val `scala-async` = "org.scala-lang.modules" %% "scala-async" % "1.0.1" //BSD 3-clause "New" or "Revised" License
  val `junit-4-13`  = "org.scalatestplus"      %% "junit-4-13"  % "3.2.10.0"
  val `mockito`     = "org.scalatestplus"      %% "mockito-3-4" % "3.2.10.0"
}

object CSW {

  // If you want to change CSW version, then update "csw.version" property in "build.properties" file
  // Same "csw.version" property should be used while running the "csw-services",
  // this makes sure that CSW library dependency and CSW services version is in sync
  val Version: String = {
    var reader: FileReader = null
    try {
      val properties = new Properties()
      reader = new FileReader("project/build.properties")
      properties.load(reader)
      val version = properties.getProperty("csw.version")
      println(s"[info]] Using CSW version [$version] ***********")
      version
    }
    catch {
      case NonFatal(e) =>
        e.printStackTrace()
        throw e
    }
    finally reader.close()
  }

  val `csw-framework` = "com.github.tmtsoftware.csw" %% "csw-framework" % Version
  val `csw-testkit`   = "com.github.tmtsoftware.csw" %% "csw-testkit"   % Version
}
