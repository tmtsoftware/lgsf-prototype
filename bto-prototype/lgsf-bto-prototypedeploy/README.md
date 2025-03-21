# bto-prototype-deploy

This module contains apps and configuration files for host deployment using 
HostConfig (https://tmtsoftware.github.io/csw/apps/hostconfig.html) and 
ContainerCmd (https://tmtsoftware.github.io/csw/framework/deploying-components.html).

An important part of making this work is ensuring the host config app (BtoPrototypeHostConfigApp) is built
with all of the necessary dependencies of the components it may run.  This is done by adding settings to the
built.sbt file:

```
lazy val `lgsf-bto-prototype-deploy` = project
  .dependsOn(
    `lgsf-bto-prototypeassembly`,
    `lgsf-bto-prototypehcd``
  )
  .settings(
    libraryDependencies ++= Dependencies.BtoPrototypeDeploy
  )
```

and in Libs.scala:

```

  val `csw-framework`  = "com.github.tmtsoftware.csw" %% "csw-framework"  % Version

```

Note: the CSW Location Service must be running before starting the components.
See https://tmtsoftware.github.io/csw/apps/cswlocationserver.html .