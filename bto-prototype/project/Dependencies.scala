import sbt._

object Dependencies {

  val BtoPrototypeAssembly = Seq(
    CSW.`csw-framework`,
    CSW.`csw-testkit` % Test,
    Libs.`scalatest` % Test,
    Libs.`junit4-interface` % Test,
    Libs.`testng-6-7` % Test,
  )

  val PacPrototypeHcd = Seq(
    CSW.`csw-framework`,
    CSW.`csw-testkit` % Test,
    Libs.`scalatest` % Test,
    Libs.`junit4-interface` % Test,
    Libs.`testng-6-7` % Test,
  )

  val BtoPrototypeDeploy = Seq(
    CSW.`csw-framework`,
    CSW.`csw-testkit` % Test
  )
}
