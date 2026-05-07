
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
    libraryDependencies ++= Dependencies.PacPrototypeHcd,
    // Fork the JVM so javaOptions (native library path) take effect
    run / fork  := true,
    Test / fork := true,
    javaOptions ++= Seq(
      // Add the Imperx SDK libs and the directory where libpac-camera-jni.so is installed.
      // Adjust the first path to wherever cmake --install places the .so.
      s"-Djava.library.path=${sys.props.getOrElse("native.lib.path", "/usr/local/lib")}:" +
        "/opt/IpxCameraSDK-1.5.0.83/lib/Linux64_x64"
    )
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
