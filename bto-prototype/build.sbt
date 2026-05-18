
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
    Compile / unmanagedJars +=
      file(sys.props.getOrElse("imperx.core.jar", "/home/jweiss/tmtsoftware/imperx-camera/imperx-core/target/scala-2.13/imperx-core_2.13-0.1.0-SNAPSHOT.jar")),
    // Fork the JVM so javaOptions (native library path) take effect
    run / fork  := true,
    Test / fork := true,
    javaOptions ++= Seq(
      // Add the imperx-camera native bridge (libimperx_bridge.so) and Imperx SDK libs.
      s"-Djava.library.path=${sys.props.getOrElse("native.lib.path", "/home/jweiss/tmtsoftware/imperx-camera/imperx-native/build")}:" +
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
