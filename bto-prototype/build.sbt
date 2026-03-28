
lazy val aggregatedProjects: Seq[ProjectReference] = Seq(
  `lgsf-bto-prototypeassembly`,
  `lgsf-pac-prototypehcd`,
  `lgsf-bto-prototypedeploy`
)

lazy val `bto-prototype-root` = project
  .in(file("."))
  .aggregate(aggregatedProjects: _*)

// assembly module
lazy val `lgsf-bto-prototypeassembly` = project
  .settings(
    libraryDependencies ++= Dependencies.BtoPrototypeAssembly
  )

// hcd module
lazy val `lgsf-pac-prototypehcd` = project
  .settings(
    libraryDependencies ++= Dependencies.PacPrototypeHcd
  )

// deploy module
lazy val `lgsf-bto-prototypedeploy` = project
  .dependsOn(
    `lgsf-bto-prototypeassembly`,
    `lgsf-pac-prototypehcd`
  )
  .enablePlugins(CswBuildInfo)
  .settings(
    libraryDependencies ++= Dependencies.BtoPrototypeDeploy
  )
