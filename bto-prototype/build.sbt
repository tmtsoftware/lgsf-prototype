
lazy val aggregatedProjects: Seq[ProjectReference] = Seq(
  `lgsf-bto-prototypeassembly`,
  `lgsf-bto-prototypehcd`,
  `lgsf-bto-prototypedeploy`
)

lazy val `bto-prototype-root` = project
  .in(file("."))
  .aggregate(aggregatedProjects: _*)

// assembly module
lazy val `lgsf-bto-prototypeassembly` = project
  .settings(
    libraryDependencies ++= Dependencies.BtoPrototypeassembly
  )

// hcd module
lazy val `lgsf-bto-prototypehcd` = project
  .settings(
    libraryDependencies ++= Dependencies.BtoPrototypehcd
  )

// deploy module
lazy val `lgsf-bto-prototypedeploy` = project
  .dependsOn(
    `lgsf-bto-prototypeassembly`,
    `lgsf-bto-prototypehcd`
  )
  .enablePlugins(CswBuildInfo)
  .settings(
    libraryDependencies ++= Dependencies.BtoPrototypeDeploy
  )
