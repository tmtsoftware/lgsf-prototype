import sbt.*

object Dependencies {

  val BtoPrototypeAssembly: Seq[ModuleID] = Seq(
    CSW.`csw-framework`,
    CSW.`csw-testkit` % Test,
    Libs.`scalatest` % Test,
    Libs.`mockito` % Test,
    Libs.`junit4-interface` % Test,
    Libs.`testng-6-7` % Test,
  )

  val PacPrototypeHcd: Seq[ModuleID] = Seq(
    CSW.`csw-framework`,
    CSW.`csw-testkit` % Test,
    Libs.`scalatest` % Test,
    Libs.`junit4-interface` % Test,
    Libs.`testng-6-7` % Test,
    Libs.`pekko-actor-testkit-typed` % Test,
    Libs.`nom-tam-fits`,
    Libs.`jnr-ffi`
  )

  val BtoPrototypeDeploy: Seq[ModuleID] = Seq(
    CSW.`csw-framework`,
    CSW.`csw-testkit` % Test
  )
}
