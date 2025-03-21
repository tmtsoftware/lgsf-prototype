import sbt._

object Dependencies {

  val BtoPrototypeassembly = Seq(
    CSW.`csw-framework`,
    CSW.`csw-testkit` % Test,
    Libs.`scalatest` % Test,
    Libs.`junit-4-13` % Test,
  )

  val BtoPrototypehcd = Seq(
    CSW.`csw-framework`,
    CSW.`csw-testkit` % Test,
    Libs.`scalatest` % Test,
    Libs.`junit-4-13` % Test,
  )

  val BtoPrototypeDeploy = Seq(
    CSW.`csw-framework`,
    CSW.`csw-testkit` % Test
  )
}
